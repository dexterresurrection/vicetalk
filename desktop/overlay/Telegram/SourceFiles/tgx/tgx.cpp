/*
Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN, based on Telegram Desktop.
Licensed under GNU GPL v3 with the OpenSSL exception, like Telegram Desktop.
*/
#include "tgx/tgx.h"

#include "tgx/tgx_protect.h"
#include "tgx/tgx_signin.h"

#include "base/call_delayed.h"
#include "base/timer.h"
#include "core/application.h"
#include "core/core_settings.h"
#include "core/file_utilities.h"
#include "mtproto/facade.h"
#include "mtproto/mtproto_proxy_data.h"
#include "ui/boxes/confirm_box.h"
#include "ui/boxes/single_choice_box.h"
#include "ui/style/style_core.h"
#include "window/main_window.h"
#include "window/window_controller.h"
#include "settings.h"

#include <QtCore/QCoreApplication>
#include <QtCore/QDateTime>
#include <QtCore/QCryptographicHash>
#include <QtCore/QDir>
#include <QtCore/QFile>
#include <QtCore/QFileInfo>
#include <QtCore/QJsonDocument>
#include <QtCore/QLocale>
#include <QtCore/QProcess>
#include <QtCore/QRandomGenerator>
#include <QtCore/QRegularExpression>
#include <QtCore/QStandardPaths>
#include <QtCore/QUrl>
#include <QtGui/QPainter>
#include <QtGui/QPixmap>
#include <QtNetwork/QHostAddress>
#include <QtNetwork/QHostInfo>
#include <QtNetwork/QNetworkAccessManager>
#include <QtNetwork/QNetworkReply>
#include <QtNetwork/QNetworkRequest>
#include <QtNetwork/QTcpSocket>

#ifdef Q_OS_WIN
#include <windows.h>
#include <shellapi.h>
#endif // Q_OS_WIN

#include <rpl/variable.h>

#include <optional>

namespace Tgx {
namespace {

constexpr auto kDirectGrace = crl::time(8000);
constexpr auto kRemember = crl::time(6 * 3600 * 1000);
constexpr auto kProbeEvery = crl::time(30 * 60 * 1000);
const auto kLocal = QString::fromLatin1("127.0.0.1");

QString ExeDir() {
	// the brand is read before QApplication exists (AppName, ApiId): ask the system, not Qt
	static const auto result = [] {
#ifdef Q_OS_WIN
		auto buffer = std::vector<wchar_t>(32768);
		const auto n = GetModuleFileNameW(nullptr, buffer.data(), DWORD(buffer.size()));
		const auto path = QString::fromWCharArray(buffer.data(), int(n));
#else // Q_OS_WIN
		const auto path = QFileInfo(QString::fromLatin1("/proc/self/exe")).symLinkTarget();
#endif // Q_OS_WIN
		const auto dir = path.isEmpty()
			? QCoreApplication::applicationDirPath()
			: QFileInfo(path).absolutePath();
		return dir + '/';
	}();
	return result;
}

QString StateDir() {
	const auto dir = cWorkingDir() + QString::fromLatin1("tdata/tgx/");
	QDir().mkpath(dir);
	return dir;
}

QJsonObject ReadJson(const QString &path) {
	auto f = QFile(path);
	if (!f.open(QIODevice::ReadOnly)) {
		return {};
	}
	return QJsonDocument::fromJson(f.readAll()).object();
}

void WriteJson(const QString &path, const QJsonObject &o) {
	auto f = QFile(path);
	if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
		f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
	}
}

QString RandomHex(int bytes) {
	auto result = QByteArray(bytes, Qt::Uninitialized);
	for (auto &c : result) {
		c = char(QRandomGenerator::system()->generate() & 0xFF);
	}
	return QString::fromLatin1(result.toHex());
}

// Our local proxy of this or an earlier run (the login is random, its look is ours: «tg» and 12 hex digits): it must be
// recognised even when bypass.json was lost, otherwise a dead local port would pass for the person's own proxy.
bool OurLocalProxy(const MTP::ProxyData &proxy) {
	static const auto look = QRegularExpression(u"^tg[0-9a-f]{12}$"_q);
	return proxy.type == MTP::ProxyData::Type::Socks5
		&& proxy.host == kLocal
		&& look.match(proxy.user).hasMatch();
}

bool PortOpen(int port) {
	if (port <= 0) {
		return false;
	}
	auto socket = QTcpSocket();
	socket.connectToHost(QHostAddress(kLocal), quint16(port));
	return socket.waitForConnected(300);
}

void OfferSignIn();

class Bypass final : public QObject {
public:
	static Bypass &Instance() {
		static auto result = new Bypass();
		return *result;
	}

	void start();
	void connectionState(int state);
	BypassMode mode() const {
		return _mode;
	}
	void setMode(BypassMode mode);
	bool active() const {
		return _active;
	}
	rpl::producer<QString> statusValue() const {
		return _status.value();
	}
	void stopXray();
	void profilesUpdated();
	void notifyStatus() {
		updateStatus();
	}

private:
	Bypass() = default;

	void tick();
	void enable(const QString &why);
	void disable(const QString &why);
	bool startXray();
	QJsonObject profiles() const;
	QJsonObject config(const QJsonArray &outbounds) const;
	void useTelegramProxy(const MTP::ProxyData &proxy);
	MTP::ProxyData localProxy() const;
	bool ourProxyCurrent() const;
	bool ourMtproto(const MTP::ProxyData &proxy) const;
	bool userProxyInUse() const;
	bool hasServers() const;
	void addMtprotoFallbacks();
	void save();
	void updateStatus();
	void note(const QString &text);

	BypassMode _mode = BypassMode::Auto;
	QJsonObject _state;
	QString _user, _pass;
	int _port = 0;
	bool _started = false;
	bool _active = false;
	int _lastState = -1;
	crl::time _connectingSince = 0;
	crl::time _lastProbe = 0;
	int _restarts = 0;
	QProcess *_xray = nullptr;
	base::Timer _timer;
	rpl::variable<QString> _status;
#ifdef Q_OS_WIN
	HANDLE _job = nullptr;
#endif // Q_OS_WIN

};

void Bypass::note(const QString &text) {
	LOG(("Tgx: %1").arg(text));
}

void Bypass::start() {
	if (_started) {
		return;
	}
	_started = true;
	_state = ReadJson(StateDir() + QString::fromLatin1("bypass.json"));
	if (!_state.contains(u"mode"_q)) {
		const auto m = GetBrand().json.value(u"bypass"_q).toObject().value(u"mode"_q).toString();
		_state.insert(u"mode"_q, (m == u"always"_q) ? 1 : (m == u"off"_q) ? 2 : 0);
	}
	_mode = BypassMode(std::clamp(_state.value(u"mode"_q).toInt(), 0, 2));
	_user = _state.value(u"user"_q).toString();
	_pass = _state.value(u"pass"_q).toString();
	if (_user.isEmpty() || _pass.isEmpty()) {
		_user = u"tg"_q + RandomHex(6);
		_pass = RandomHex(16);
		_state.insert(u"user"_q, _user);
		_state.insert(u"pass"_q, _pass);
	}
	save();
	addMtprotoFallbacks();
	QObject::connect(qApp, &QCoreApplication::aboutToQuit, [=] { stopXray(); });
	const auto key = u"need_until"_q;
	const auto remembered = qint64(_state.value(key).toDouble()) > QDateTime::currentMSecsSinceEpoch();
	if (_mode == BypassMode::Always || (_mode == BypassMode::Auto && remembered)) {
		enable(u"start"_q);
	} else if (ourProxyCurrent()) {
		disable(u"stale local proxy from the last run"_q);
	}
	_timer.setCallback([=] { tick(); });
	_timer.callEach(2000);
	updateStatus();
	note(u"brand %1, api %2, mode %3"_q.arg(GetBrand().line).arg(GetBrand().apiId > 0 ? u"set"_q : u"MISSING"_q).arg(int(_mode)));
}

void Bypass::save() {
	WriteJson(StateDir() + QString::fromLatin1("bypass.json"), _state);
}

void Bypass::setMode(BypassMode mode) {
	_mode = mode;
	_state.insert(u"mode"_q, int(mode));
	save();
	if (mode == BypassMode::Always) {
		enable(u"mode"_q);
	} else if (mode == BypassMode::Off) {
		disable(u"mode"_q);
	} else {
		_connectingSince = 0;
	}
	updateStatus();
}

void Bypass::connectionState(int state) {
	if (state != _lastState) {
		_lastState = state;
		note(u"telegram state %1 (%2)"_q.arg(state).arg(_active ? u"bypass"_q : u"direct"_q));
	}
	if (state == MTP::ConnectedState) {
		_connectingSince = 0;
	} else if (!_connectingSince) {
		_connectingSince = crl::now();
	}
	updateStatus();
}

void Bypass::tick() {
	const auto now = crl::now();
	if (_mode == BypassMode::Auto) {
		if (!_active
			&& _connectingSince
			&& !userProxyInUse()
			&& now - _connectingSince >= kDirectGrace
			&& !hasServers()) {
			OfferSignIn(); // nothing to bypass with until the sign-in to our account
		} else if (!_active
			&& _connectingSince
			&& !userProxyInUse()
			&& now - _connectingSince >= kDirectGrace) {
			_state.insert(u"need_until"_q, double(QDateTime::currentMSecsSinceEpoch() + kRemember));
			save();
			enable(u"auto: no direct connection for %1 s"_q.arg((now - _connectingSince) / 1000));
		} else if (_active && now - _lastProbe >= kProbeEvery) {
			_lastProbe = now;
			// off the UI thread: up to 4 TCP connects of 3 s each
			crl::async([=] {
				auto ok = 0;
				for (const auto ip : { "149.154.175.50", "149.154.167.51", "149.154.175.100", "149.154.167.91" }) {
					auto socket = QTcpSocket();
					socket.connectToHost(QHostAddress(QString::fromLatin1(ip)), 443);
					if (socket.waitForConnected(3000) && ++ok >= 2) {
						break;
					}
				}
				crl::on_main([=] {
					if (ok >= 2 && _active && _mode == BypassMode::Auto) {
						_state.insert(u"need_until"_q, 0.);
						save();
						disable(u"auto: direct path answers again"_q);
					}
				});
			});
		}
	}
	if (_active && _mode != BypassMode::Off && (!_xray || _xray->state() == QProcess::NotRunning) && _restarts < 20) {
		++_restarts;
		if (startXray()) {
			useTelegramProxy(localProxy());
		}
	}
}

MTP::ProxyData Bypass::localProxy() const {
	auto result = MTP::ProxyData();
	result.type = MTP::ProxyData::Type::Socks5;
	result.host = kLocal;
	result.port = uint32(_port);
	result.user = _user;
	result.password = _pass;
	return result;
}

bool Bypass::ourProxyCurrent() const {
	const auto &proxy = Core::App().settings().proxy();
	const auto &selected = proxy.selected();
	return proxy.isEnabled()
		&& OurLocalProxy(selected);
}

bool Bypass::ourMtproto(const MTP::ProxyData &proxy) const {
	if (proxy.type != MTP::ProxyData::Type::Mtproto) {
		return false;
	}
	for (const auto &v : profiles().value(u"mtproto"_q).toArray()) {
		const auto o = v.toObject();
		if (o.value(u"server"_q).toString() == proxy.host
			&& uint32(o.value(u"port"_q).toInt()) == proxy.port) {
			return true;
		}
	}
	return false;
}

bool Bypass::userProxyInUse() const {
	const auto &proxy = Core::App().settings().proxy();
	return proxy.isEnabled() && !ourProxyCurrent() && !ourMtproto(proxy.selected());
}

void Bypass::profilesUpdated() {
	_state.remove(u"mtproto_added"_q);
	addMtprotoFallbacks();
	if (_active && !hasServers()) {
		disable(u"no bypass servers any more"_q);
	} else if (_active) {
		stopXray();
		if (startXray()) {
			useTelegramProxy(localProxy());
		}
	} else if (_mode == BypassMode::Always) {
		enable(u"profiles arrived"_q);
	}
	updateStatus();
}

void Bypass::addMtprotoFallbacks() {
	const auto list = profiles().value(u"mtproto"_q).toArray();
	const auto version = u"%1:%2"_q.arg(profiles().value(u"version"_q).toString()).arg(list.size());
	if (_state.value(u"mtproto_added"_q).toString() == version) {
		return;
	}
	auto &settings = Core::App().settings().proxy();
	for (const auto &v : list) {
		const auto o = v.toObject();
		auto proxy = MTP::ProxyData();
		proxy.type = MTP::ProxyData::Type::Mtproto;
		proxy.host = o.value(u"server"_q).toString();
		proxy.port = uint32(o.value(u"port"_q).toInt(443));
		proxy.password = o.value(u"secret"_q).toString();
		if (proxy.valid() && settings.indexInList(proxy) < 0) {
			settings.addToList(proxy);
		}
	}
	settings.setProxyRotationEnabled(true);
	Core::App().saveSettingsDelayed();
	_state.insert(u"mtproto_added"_q, version);
	save();
}

void Bypass::enable(const QString &why) {
	if (userProxyInUse()) {
		return;
	} else if (!hasServers()) {
		if (ourProxyCurrent()) {
			disable(u"no bypass servers, stale local proxy"_q);
		}
		return;
	}
	note(u"enable: "_q + why);
	_active = true;
	_restarts = 0;
	_lastProbe = crl::now();
	if (startXray()) {
		useTelegramProxy(localProxy());
	} else {
		// no profiles or the core did not start: our MTProto proxies with Telegram's own rotation
		for (const auto &proxy : Core::App().settings().proxy().list()) {
			if (ourMtproto(proxy)) {
				useTelegramProxy(proxy);
				break;
			}
		}
	}
	updateStatus();
}

void Bypass::disable(const QString &why) {
	note(u"disable: "_q + why);
	_active = false;
	const auto &proxy = Core::App().settings().proxy();
	if (ourProxyCurrent() || (proxy.isEnabled() && ourMtproto(proxy.selected()))) {
		Core::App().setCurrentProxy(proxy.selected(), MTP::ProxyData::Settings::System);
		Core::App().saveSettingsDelayed();
	}
	stopXray();
	updateStatus();
}

void Bypass::useTelegramProxy(const MTP::ProxyData &value) {
	auto &settings = Core::App().settings().proxy();
	// the local port changes between runs: drop our old local entries
	auto &list = settings.list();
	list.erase(ranges::remove_if(list, [&](const MTP::ProxyData &p) {
		return OurLocalProxy(p) && (p != value);
	}), end(list));
	if (settings.indexInList(value) < 0) {
		settings.insertToList(0, value);
	}
	settings.setUseProxyForCalls(true);
	Core::App().setCurrentProxy(value, MTP::ProxyData::Settings::Enabled);
	Core::App().saveSettingsDelayed();
}

bool Bypass::hasServers() const {
	const auto list = profiles();
	return !list.value(u"xray"_q).toArray().isEmpty()
		|| !list.value(u"mtproto"_q).toArray().isEmpty();
}

QJsonObject Bypass::profiles() const {
	const auto cached = ReadJson(StateDir() + QString::fromLatin1("profiles.json"));
	if (!cached.value(u"xray"_q).toArray().isEmpty()) {
		return cached;
	}
	return GetBrand().json.value(u"bypass"_q).toObject();
}

QJsonObject Bypass::config(const QJsonArray &outbounds) const {
	auto outs = QJsonArray();
	for (auto i = 0; i != outbounds.size(); ++i) {
		auto o = outbounds[i].toObject();
		o.insert(u"tag"_q, u"px-%1"_q.arg(i));
		// server names are resolved here (Go on Windows does it too, but this keeps one path for all platforms)
		auto settings = o.value(u"settings"_q).toObject();
		for (const auto key : { u"vnext"_q, u"servers"_q }) {
			auto servers = settings.value(key).toArray();
			for (auto j = 0; j != servers.size(); ++j) {
				auto s = servers[j].toObject();
				const auto host = s.value(u"address"_q).toString();
				if (!host.isEmpty() && QHostAddress(host).isNull()) {
					const auto info = QHostInfo::fromName(host);
					for (const auto &a : info.addresses()) {
						if (a.protocol() == QAbstractSocket::IPv4Protocol) {
							s.insert(u"address"_q, a.toString());
							break;
						}
					}
				}
				servers[j] = s;
			}
			if (!servers.isEmpty()) {
				settings.insert(key, servers);
			}
		}
		o.insert(u"settings"_q, settings);
		outs.append(o);
	}
	outs.append(QJsonObject{ { u"tag"_q, u"block"_q }, { u"protocol"_q, u"blackhole"_q } });
	const auto socks = QJsonObject{
		{ u"tag"_q, u"in"_q },
		{ u"listen"_q, kLocal },
		{ u"port"_q, _port },
		{ u"protocol"_q, u"socks"_q },
		{ u"settings"_q, QJsonObject{
			{ u"auth"_q, u"password"_q },
			{ u"udp"_q, true },
			{ u"ip"_q, kLocal },
			{ u"accounts"_q, QJsonArray{ QJsonObject{ { u"user"_q, _user }, { u"pass"_q, _pass } } } },
		} },
	};
	auto routing = QJsonObject{ { u"domainStrategy"_q, u"AsIs"_q } };
	auto result = QJsonObject{
		{ u"log"_q, QJsonObject{ { u"loglevel"_q, u"warning"_q }, { u"access"_q, u"none"_q } } },
		{ u"inbounds"_q, QJsonArray{ socks } },
		{ u"outbounds"_q, outs },
		{ u"dns"_q, QJsonObject{ { u"servers"_q, QJsonArray{ u"1.1.1.1"_q, u"8.8.8.8"_q, u"77.88.8.8"_q } } } },
	};
	if (outbounds.size() > 1) {
		routing.insert(u"balancers"_q, QJsonArray{ QJsonObject{
			{ u"tag"_q, u"bal"_q },
			{ u"selector"_q, QJsonArray{ u"px-"_q } },
			{ u"strategy"_q, QJsonObject{ { u"type"_q, u"leastPing"_q } } },
		} });
		routing.insert(u"rules"_q, QJsonArray{ QJsonObject{
			{ u"type"_q, u"field"_q },
			{ u"inboundTag"_q, QJsonArray{ u"in"_q } },
			{ u"balancerTag"_q, u"bal"_q },
		} });
		result.insert(u"observatory"_q, QJsonObject{
			{ u"subjectSelector"_q, QJsonArray{ u"px-"_q } },
			{ u"probeUrl"_q, u"https://www.gstatic.com/generate_204"_q },
			{ u"probeInterval"_q, u"3m"_q },
			{ u"enableConcurrency"_q, true },
		});
	} else {
		routing.insert(u"rules"_q, QJsonArray{ QJsonObject{
			{ u"type"_q, u"field"_q },
			{ u"inboundTag"_q, QJsonArray{ u"in"_q } },
			{ u"outboundTag"_q, u"px-0"_q },
		} });
	}
	result.insert(u"routing"_q, routing);
	return result;
}

bool Bypass::startXray() {
	if (_xray && _xray->state() != QProcess::NotRunning && PortOpen(_port)) {
		return true;
	}
	stopXray();
	const auto outbounds = profiles().value(u"xray"_q).toArray();
	if (outbounds.isEmpty()) {
		note(u"no xray profiles"_q);
		return false;
	}
#ifdef Q_OS_WIN
	const auto bin = ExeDir() + u"xray.exe"_q;
#else // Q_OS_WIN
	const auto bin = ExeDir() + u"xray"_q;
#endif // Q_OS_WIN
	if (!QFile::exists(bin)) {
		note(u"no xray core next to the executable: "_q + bin);
		return false;
	}
	_port = _state.value(u"port"_q).toInt();
	if (!_port || PortOpen(_port)) {
		_port = 20000 + int(QRandomGenerator::system()->bounded(40000));
		_state.insert(u"port"_q, _port);
		save();
	}
	const auto cfg = StateDir() + u"xray.json"_q;
	WriteJson(cfg, config(outbounds));
	QFile::setPermissions(cfg, QFileDevice::ReadOwner | QFileDevice::WriteOwner);
	_xray = new QProcess(this);
	_xray->setProgram(bin);
	_xray->setArguments({ u"run"_q, u"-c"_q, cfg });
	_xray->setWorkingDirectory(StateDir());
	_xray->setProcessChannelMode(QProcess::MergedChannels);
	QObject::connect(_xray, &QProcess::readyRead, [=] {
		while (_xray && _xray->canReadLine()) {
			note(u"xray: "_q + QString::fromUtf8(_xray->readLine()).trimmed());
		}
	});
	_xray->start();
	if (!_xray->waitForStarted(5000)) {
		note(u"xray did not start: "_q + _xray->errorString());
		return false;
	}
#ifdef Q_OS_WIN
	// the core dies with us even after a crash: a job object closed with the last handle
	if (!_job) {
		_job = CreateJobObjectW(nullptr, nullptr);
		auto info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
		info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
		SetInformationJobObject(_job, JobObjectExtendedLimitInformation, &info, sizeof(info));
	}
	if (const auto process = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, FALSE, DWORD(_xray->processId()))) {
		AssignProcessToJobObject(_job, process);
		CloseHandle(process);
	}
#endif // Q_OS_WIN
	for (auto i = 0; i != 40; ++i) {
		if (PortOpen(_port)) {
			note(u"xray up on %1, outbounds %2"_q.arg(_port).arg(outbounds.size()));
			return true;
		}
		if (_xray->state() == QProcess::NotRunning) {
			break;
		}
		_xray->waitForFinished(100);
	}
	note(u"xray did not open its port"_q);
	return false;
}

void Bypass::stopXray() {
	if (const auto xray = base::take(_xray)) {
		xray->disconnect();
		xray->kill();
		xray->waitForFinished(2000);
		xray->deleteLater();
	}
}

void Bypass::updateStatus() {
	const auto m = (_mode == BypassMode::Always)
		? T("всегда", "always")
		: (_mode == BypassMode::Off)
		? T("выключен", "off")
		: T("авто", "auto");
	_status = m + u" · "_q + (_active
		? T("сейчас через обход", "now via bypass")
		: T("сейчас напрямую", "now direct"));
}


// ---------------------------------------------------------------- CloudVPN account (the bypass is part of the subscription)

constexpr auto kTestApiId = 17349; // Telegram Desktop's public development key: CI builds only

// The accounts the bypass can come from: CloudVPN (cabinet.zjd.uk) or, in the Vice line, a choice of Vice VPN (vice.zjd.uk)
// and CloudVPN. A token goes only to the addresses of its own account.
enum class Service {
	Cloud,
	Vice,
};

std::vector<Service> Services() {
	return IsVice()
		? std::vector<Service>{ Service::Vice, Service::Cloud }
		: std::vector<Service>{ Service::Cloud };
}

QString ServiceId(Service service) {
	return (service == Service::Vice) ? u"vice"_q : u"cloud"_q;
}

QString ServiceName(Service service) {
	return (service == Service::Vice) ? u"Vice VPN"_q : u"CloudVPN"_q;
}

QString ServiceOrigin(Service service) {
	return QString::fromLatin1((service == Service::Vice) ? kAccountVice : kAccountCloud);
}

// The mirror of CloudVPN is reachable through the Russian relay.
QStringList Origins(Service service) {
	const auto key = (service == Service::Vice) ? u"test_origin_vice"_q : u"test_origin"_q;
	const auto test = GetBrand().json.value(key).toString();
	if (!test.isEmpty() && GetBrand().apiId == kTestApiId) {
		return { test };
	}
	return (service == Service::Vice)
		? QStringList{ QString::fromLatin1(kAccountVice) }
		: QStringList{ QString::fromLatin1(kAccountCloud), QString::fromLatin1(kAccountCloudMirror) };
}

// One session at a time, its account is written next to it. The account changes only through a sign-out, which leaves
// nothing of the former one: no token, no servers.
class Account final : public QObject {
public:
	static Account &Instance() {
		static auto result = new Account();
		return *result;
	}
	bool loggedIn() const {
		return !_refresh.isEmpty();
	}
	Service service() const {
		return _service;
	}
	rpl::producer<QString> statusValue() const {
		return _status.value();
	}
	void load() {
		const auto o = ReadJson(path());
		const auto box = QByteArray::fromBase64(o.value(u"refresh_protected"_q).toString().toLatin1());
		const auto plain = o.value(u"refresh"_q).toString(); // version 0.1.0 and Linux
		_refresh = box.isEmpty() ? plain : QString::fromUtf8(Unprotect(box));
		_service = (o.value(u"account"_q).toString() == u"vice"_q) ? Service::Vice : Service::Cloud;
		_name = o.value(u"name"_q).toString();
		if (!box.isEmpty() && _refresh.isEmpty()) {
			LOG(("Tgx: account: the saved session is not readable on this Windows account"));
		}
#ifdef Q_OS_WIN
		if (!plain.isEmpty()) {
			save(); // protected from now on
		}
#endif // Q_OS_WIN
		updateStatus();
		_timer.setCallback([=] { fetchProfile(nullptr); });
		_timer.callEach(6 * 3600 * 1000);
		if (loggedIn()) {
			fetchProfile(nullptr);
		}
	}
	void logout() {
		++_run;
		forget();
	}
	// no callbacks after cancelLogin()
	void startLogin(Service service, Fn<void(QString code, QString url)> onCode, Fn<void(bool ok, QString error)> onDone) {
		const auto run = ++_run;
		request(service, u"POST"_q, u"/cabinet/auth/device/request"_q, QByteArray("{}"), QString(), [=](int code, QByteArray body) {
			if (_run != run) {
				return;
			}
			const auto d = QJsonDocument::fromJson(body).object();
			if (code != 200 || d.value(u"token"_q).toString().isEmpty()) {
				onDone(false, T("Кабинет не отвечает", "The cabinet does not answer"));
				return;
			}
			onCode(d.value(u"user_code"_q).toString(), d.value(u"verification_url"_q).toString());
			poll(run, service, d.value(u"token"_q).toString(), crl::now() + d.value(u"expires_in"_q).toInt(600) * crl::time(1000), onDone);
		});
	}
	void cancelLogin() {
		++_run;
	}
	void fetchProfile(Fn<void(QString error)> done) {
		const auto gen = _gen;
		const auto service = _service;
		withAccess([=](QString access) {
			if (gen != _gen) {
				return; // signed out or another account meanwhile
			} else if (access.isEmpty()) {
				if (done) done(T("Войдите в аккаунт ", "Sign in to ") + ServiceName(service));
				return;
			}
			request(service, u"GET"_q, u"/cabinet/app/talk-profile"_q, QByteArray(), access, [=](int code, QByteArray body) {
				if (gen != _gen) {
					return; // these servers are not used
				}
				auto error = QString();
				if (code == 200 && QJsonDocument::fromJson(body).object().contains(u"xray"_q)) {
					auto f = QFile(StateDir() + u"profiles.json"_q);
					if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
						f.write(body);
						f.close();
					}
					LOG(("Tgx: profiles from the account: %1 xray").arg(QJsonDocument::fromJson(body).object().value(u"xray"_q).toArray().size()));
					Bypass::Instance().profilesUpdated();
				} else {
					const auto e = QJsonDocument::fromJson(body).object().value(u"error"_q).toString();
					error = (e == u"no_device"_q)
						? T("Сначала подключитесь к VPN на любом устройстве — обход берёт серверы вашей подписки ", "Connect the VPN on any device first: ") + ServiceName(service)
						: (e == u"subscription_inactive"_q || e == u"no_subscription"_q)
						? T("Подписка не активна — обход входит в подписку ", "The subscription is not active: ") + ServiceName(service)
						: T("Кабинет ответил ", "The cabinet answered ") + QString::number(code);
					LOG(("Tgx: profiles: %1").arg(error));
				}
				updateStatus();
				if (done) done(error);
			});
		});
	}

private:
	Account() = default;

	static QString path() {
		return StateDir() + u"account.json"_q;
	}
	void forget() {
		++_gen;
		_refresh = _access = _name = QString();
		QFile::remove(path());
		QFile::remove(StateDir() + u"profiles.json"_q);
		Bypass::Instance().profilesUpdated();
		updateStatus();
	}
	void save() {
		auto o = QJsonObject{ { u"account"_q, ServiceId(_service) }, { u"name"_q, _name } };
#ifdef Q_OS_WIN
		const auto box = Protect(_refresh.toUtf8());
		if (box.isEmpty()) {
			LOG(("Tgx: account: the session could not be protected, it is kept until the program is closed"));
		} else {
			o.insert(u"refresh_protected"_q, QString::fromLatin1(box.toBase64()));
		}
#else // Q_OS_WIN
		o.insert(u"refresh"_q, _refresh);
#endif // Q_OS_WIN
		WriteJson(path(), o);
		QFile::setPermissions(path(), QFileDevice::ReadOwner | QFileDevice::WriteOwner);
	}
	void apply(const QJsonObject &a) {
		if (!a.value(u"refresh_token"_q).toString().isEmpty()) {
			_refresh = a.value(u"refresh_token"_q).toString();
		}
		_access = a.value(u"access_token"_q).toString();
		_accessUntil = crl::now() + (a.value(u"expires_in"_q).toInt(900) - 60) * crl::time(1000);
		const auto user = a.value(u"user"_q).toObject();
		if (!user.isEmpty()) {
			_name = user.value(u"first_name"_q).toString(user.value(u"username"_q).toString());
		}
		save();
	}
	void withAccess(Fn<void(QString)> then) {
		if (!_access.isEmpty() && crl::now() < _accessUntil) {
			then(_access);
			return;
		}
		if (_refresh.isEmpty()) {
			then(QString());
			return;
		}
		const auto gen = _gen;
		const auto body = QJsonDocument(QJsonObject{ { u"refresh_token"_q, _refresh }, { u"rotate"_q, true } }).toJson(QJsonDocument::Compact);
		request(_service, u"POST"_q, u"/cabinet/auth/refresh"_q, body, QString(), [=](int code, QByteArray answer) {
			if (gen != _gen) {
				return; // signed out or another account meanwhile: nothing of it is kept
			} else if (code == 200) {
				apply(QJsonDocument::fromJson(answer).object());
				then(_access);
			} else {
				if (code == 401) { // signed out in the cabinet; no connection or its fault is not a sign-out
					forget();
				}
				then(QString());
			}
		});
	}
	void poll(int run, Service service, QString token, crl::time until, Fn<void(bool, QString)> onDone) {
		if (_run != run) {
			return;
		} else if (crl::now() > until) {
			onDone(false, T("Код устарел, попробуйте ещё раз", "The code expired, try again"));
			return;
		}
		base::call_delayed(2000, this, [=] {
			if (_run != run) {
				return;
			}
			const auto body = QJsonDocument(QJsonObject{ { u"token"_q, token } }).toJson(QJsonDocument::Compact);
			request(service, u"POST"_q, u"/cabinet/auth/deeplink/poll"_q, body, QString(), [=](int code, QByteArray answer) {
				if (_run != run) {
					return; // cancelled meanwhile: the new session is not kept
				} else if (code == 200) {
					++_gen;
					_refresh = _access = _name = QString();
					_service = service;
					apply(QJsonDocument::fromJson(answer).object());
					LOG(("Tgx: sign-in: done (%1)").arg(ServiceId(service)));
					updateStatus();
					fetchProfile([=](QString error) { onDone(true, error); });
				} else if (code == 410) {
					onDone(false, T("Код устарел, попробуйте ещё раз", "The code expired, try again"));
				} else {
					poll(run, service, token, until, onDone);
				}
			});
		});
	}
	void request(Service service, QString method, QString path, QByteArray body, QString bearer, Fn<void(int, QByteArray)> done, int origin = 0) {
		const auto origins = Origins(service);
		auto req = QNetworkRequest(QUrl(origins[origin] + u"/api"_q + path));
		req.setHeader(QNetworkRequest::ContentTypeHeader, u"application/json"_q);
		req.setRawHeader("User-Agent", (GetBrand().name + u" Windows"_q).toUtf8());
		req.setRawHeader("x-device-os", "Windows");
		// a token never follows a redirect to another address
		req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
		if (!bearer.isEmpty()) {
			req.setRawHeader("Authorization", "Bearer " + bearer.toUtf8());
		}
		req.setTransferTimeout(20000);
		const auto reply = (method == u"GET"_q) ? _net.get(req) : _net.post(req, body);
		QObject::connect(reply, &QNetworkReply::finished, this, [=] {
			reply->deleteLater();
			const auto code = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
			if ((code == 0 || code >= 500) && origin + 1 < origins.size()) {
				request(service, method, path, body, bearer, done, origin + 1);
				return;
			}
			done(code, reply->readAll());
		});
	}
	void updateStatus() {
		_status = loggedIn()
			? (ServiceName(_service) + u" · "_q + (_name.isEmpty() ? T("вход выполнен", "signed in") : _name))
			: T("не выполнен — обход входит в подписку", "not signed in — the bypass is part of the subscription");
	}

	QNetworkAccessManager _net;
	Service _service = Service::Cloud;
	QString _refresh, _access, _name;
	crl::time _accessUntil = 0;
	int _run = 0;
	int _gen = 0; // grows with every sign-in and sign-out
	base::Timer _timer;
	rpl::variable<QString> _status;

};

// ---------------------------------------------------------------- in-app update: the installer is downloaded here (resume,
// three mirrors), its sha256 is checked against downloads.json, then it runs silently and opens the program again

class UpdateDownload final : public QObject {
public:
	UpdateDownload(QStringList urls, QString name, QString sha256)
	: _urls(std::move(urls))
	, _path(StateDir() + u"update/"_q + name)
	, _sha256(sha256.toLower()) {
		QDir().mkpath(StateDir() + u"update/"_q);
	}
	void start() {
		next();
	}

private:
	void next() {
		if (_index >= _urls.size()) {
			finish(T("Не удалось скачать обновление. Его можно взять на странице загрузок.", "Could not download the update. You can get it on the download page."));
			return;
		}
		_part.setFileName(_path + u".part"_q);
		if (!_part.open(QIODevice::ReadWrite)) {
			finish(T("Нет места для обновления", "No room for the update"));
			return;
		}
		_part.seek(_part.size());
		const auto have = _part.size();
		auto req = QNetworkRequest(QUrl(_urls[_index]));
		req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
		req.setTransferTimeout(60000);
		if (have > 0) {
			req.setRawHeader("Range", "bytes=" + QByteArray::number(have) + "-");
		}
		_reply = _net.get(req);
		QObject::connect(_reply, &QNetworkReply::readyRead, this, [=] {
			const auto code = _reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
			if (code == 200 && _part.size() > 0 && have > 0 && !_restarted) {
				_restarted = true;   // the server ignored Range: start over
				_part.resize(0);
			}
			_part.write(_reply->readAll());
		});
		QObject::connect(_reply, &QNetworkReply::finished, this, [=] {
			const auto code = _reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
			const auto ok = (_reply->error() == QNetworkReply::NoError) || code == 416;
			_reply->deleteLater();
			_part.close();
			_restarted = false;
			if (!ok) {
				if (_tries++ < 4) {
					base::call_delayed(3000, this, [=] { next(); });
				} else {
					_tries = 0;
					++_index;
					next();
				}
				return;
			}
			auto file = QFile(_path + u".part"_q);
			auto hash = QCryptographicHash(QCryptographicHash::Sha256);
			if (file.open(QIODevice::ReadOnly)) {
				hash.addData(&file);
				file.close();
			}
			if (_sha256.isEmpty() || QString::fromLatin1(hash.result().toHex()) == _sha256) {
				QFile::remove(_path);
				QFile::rename(_path + u".part"_q, _path);
				install();
			} else {
				LOG(("Tgx: update sha256 mismatch from %1").arg(_urls[_index]));
				QFile::remove(_path + u".part"_q);
				++_index;
				next();
			}
		});
	}
	void install() {
		LOG(("Tgx: update downloaded: %1").arg(_path));
#ifdef Q_OS_WIN
		const auto path = QDir::toNativeSeparators(_path).toStdWString();
		const auto result = reinterpret_cast<INT_PTR>(ShellExecuteW(nullptr, L"runas", path.c_str(), L"/S /RUN", nullptr, SW_SHOWNORMAL));
		if (result <= 32) {
			finish(T("Установка отменена", "The installation was cancelled"));
			return;
		}
		LOG(("Tgx: update installer started"));
		Core::Quit();
#else // Q_OS_WIN
		File::OpenUrl(u"file://"_q + _path);
#endif // Q_OS_WIN
		deleteLater();
	}
	void finish(QString text) {
		if (const auto window = Core::App().activePrimaryWindow()) {
			window->show(Ui::MakeInformBox(text));
		}
		deleteLater();
	}

	QNetworkAccessManager _net;
	QNetworkReply *_reply = nullptr;
	QStringList _urls;
	QString _path, _sha256;
	QFile _part;
	int _index = 0;
	int _tries = 0;
	bool _restarted = false;

};

void CheckUpdates() {
	const auto url = GetBrand().json.value(u"downloads"_q).toString(u"https://cabinet.zjd.uk/app/downloads.json"_q);
	const auto manager = new QNetworkAccessManager(qApp);
	const auto reply = manager->get(QNetworkRequest(QUrl(url)));
	QObject::connect(reply, &QNetworkReply::finished, [=] {
		reply->deleteLater();
		manager->deleteLater();
		const auto root = QJsonDocument::fromJson(reply->readAll()).object();
		const auto product = root.value(u"products"_q).toObject().value(GetBrand().product).toObject();
		const auto latest = product.value(u"version"_q).toString();
		const auto mine = GetBrand().json.value(u"version"_q).toString();
		LOG(("Tgx: update check: mine %1, latest %2").arg(mine).arg(latest));
		if (latest.isEmpty() || mine.isEmpty()) {
			return;
		}
		const auto a = latest.split('.'), b = mine.split('.');
		auto newer = false;
		for (auto i = 0; i != int(std::max(a.size(), b.size())); ++i) {
			const auto x = (i < a.size()) ? a[i].toInt() : 0;
			const auto y = (i < b.size()) ? b[i].toInt() : 0;
			if (x != y) {
				newer = (x > y);
				break;
			}
		}
		if (!newer) {
			return;
		}
		auto urls = QStringList();
		auto name = QString();
		auto sha = QString();
		for (const auto &v : product.value(u"files"_q).toArray()) {
			const auto f = v.toObject();
			if (f.value(u"name"_q).toString().endsWith(u".exe"_q)) {
				name = f.value(u"name"_q).toString();
				sha = f.value(u"sha256"_q).toString();
				for (const auto key : { u"bucket"_q, u"server"_q, u"github"_q }) {   // the RU mirror first
					const auto link = f.value(key).toString();
					if (!link.isEmpty()) {
						urls.push_back(link);
					}
				}
				break;
			}
		}
		if (urls.isEmpty() || !name.contains(QRegularExpression(u"^[A-Za-z0-9._-]+\\.exe$"_q))) {
			return;
		}
#ifdef Q_OS_WIN
		if (qEnvironmentVariable("TGX_CI_AUTO_UPDATE") == u"1"_q) {   // the runner check cannot press our custom-drawn buttons
			(new UpdateDownload(urls, name, sha))->start();
			return;
		}
#endif // Q_OS_WIN
		if (const auto window = Core::App().activePrimaryWindow()) {
			window->show(Ui::MakeConfirmBox({
				.text = GetBrand().name + u" "_q + latest + u"\n\n"_q + product.value(u"notes"_q).toString()
					+ u"\n\n"_q + T("Программа сама скачает обновление и перезапустится.", "The program downloads the update itself and restarts."),
				.confirmed = [=](Fn<void()> close) {
					close();
#ifdef Q_OS_WIN
					(new UpdateDownload(urls, name, sha))->start();
#else // Q_OS_WIN
					File::OpenUrl(urls[0]);
#endif // Q_OS_WIN
				},
				.confirmText = T("Обновить", "Update"),
				.cancelText = T("Позже", "Later"),
				.title = T("Доступно обновление", "Update available"),
			}));
		}
	});
}

} // namespace

const Brand &GetBrand() {
	static const auto result = [] {
		auto b = Brand();
		// brand.json — line, name, theme (written by the shared installer kit on every install/update);
		// talk.json — api keys, bypass profiles, product (built on our side at release time, never in the repository)
		b.json = ReadJson(ExeDir() + QString::fromLatin1("brand.json"));
		const auto gram = ReadJson(ExeDir() + QString::fromLatin1("talk.json"));
		for (auto i = gram.begin(); i != gram.end(); ++i) {
			b.json.insert(i.key(), i.value());
		}
		b.line = (b.json.value(u"brand"_q).toString() == u"vice"_q) ? u"vice"_q : u"cloud"_q;
		b.name = b.json.value(u"name"_q).toString((b.line == u"vice"_q) ? u"Vice Talk"_q : u"Cloud Talk"_q);
		b.product = b.json.value(u"product"_q).toString((b.line == u"vice"_q) ? u"vice-talk-win"_q : u"talk-win"_q);
		b.apiId = b.json.value(u"api_id"_q).toInt();
		b.apiHash = b.json.value(u"api_hash"_q).toString();
		if (b.apiHash.size() != 32) {
			b.apiId = 0;
			b.apiHash = QString();
		}
		return b;
	}();
	return result;
}

bool IsVice() {
	return GetBrand().line == u"vice"_q;
}

bool Ru() {
	const auto l = QLocale::system().language();
	return l == QLocale::Russian || l == QLocale::Ukrainian || l == QLocale::Belarusian || l == QLocale::Kazakh;
}

QString T(const char *ru, const char *en) {
	return QString::fromUtf8(Ru() ? ru : en);
}

QString IntroAbout() {
	return T(
		"Неофициальный клиент Telegram от CloudVPN.\nСо встроенным обходом блокировок.",
		"Unofficial Telegram client by CloudVPN.\nWith a built-in bypass of blocks.");
}

void PaintIntroLogo(QPainter &p, int left, int top, int width) {
	const auto size = style::ConvertScale(112);
	const auto ratio = style::DevicePixelRatio();
	static auto cached = QPixmap();
	if (cached.width() != size * ratio) {
		auto image = Window::LogoNoMargin().scaled(
			size * ratio,
			size * ratio,
			Qt::IgnoreAspectRatio,
			Qt::SmoothTransformation);
		image.setDevicePixelRatio(ratio);
		cached = QPixmap::fromImage(std::move(image));
	}
	p.drawPixmap(left + (width - size) / 2, top, cached);
}

int ApiIdValue() {
	return GetBrand().apiId;
}

QString ApiHashValue() {
	return GetBrand().apiHash;
}

QString AppNameValue::utf16() const {
	return GetBrand().name;
}

QByteArray AppNameValue::utf8() const {
	return GetBrand().name.toUtf8();
}

const char *AppNameValue::data() const {
	static const auto result = GetBrand().name.toUtf8();
	return result.constData();
}

namespace {
void SignInForChecks();
} // namespace

void Start() {
	Bypass::Instance().start();
	Account::Instance().load();
	base::call_delayed(15000, qApp, [] { CheckUpdates(); });
	base::call_delayed(14000, qApp, [] { SignInForChecks(); });
}

void ConnectionStateChanged(int state) {
	Bypass::Instance().connectionState(state);
}

BypassMode GetBypassMode() {
	return Bypass::Instance().mode();
}

void SetBypassMode(BypassMode mode) {
	Bypass::Instance().setMode(mode);
}

std::vector<QString> BypassModeNames() {
	return {
		T("Автоматически (рекомендуется)", "Automatic (recommended)"),
		T("Всегда через обход", "Always via bypass"),
		T("Выключен — только напрямую", "Off — direct only"),
	};
}

QString BypassTitle() {
	return T("Обход блокировок", "Bypass");
}

rpl::producer<QString> BypassStatusValue() {
	return Bypass::Instance().statusValue();
}

bool BypassActive() {
	return Bypass::Instance().active();
}

QString AccountTitle() {
	const auto &account = Account::Instance();
	return account.loggedIn()
		? (T("Аккаунт ", "Account ") + ServiceName(account.service()))
		: IsVice()
		? T("Аккаунт Vice VPN или Cloud VPN", "Vice VPN or Cloud VPN account")
		: T("Аккаунт CloudVPN", "CloudVPN account");
}

rpl::producer<QString> AccountStatusValue() {
	return Account::Instance().statusValue();
}

namespace {

Window::Controller *ActiveWindow() {
	return Core::App().activePrimaryWindow();
}

void Inform(const QString &text) {
	if (const auto w = ActiveWindow(); w && !text.isEmpty()) {
		w->show(Ui::MakeInformBox(text));
	}
}

// the code is confirmed in the cabinet
void SignInByCode(Service service) {
	const auto url = std::make_shared<QString>();
	const auto box = std::make_shared<base::weak_qptr<Ui::GenericBox>>();
	Account::Instance().startLogin(service, [=](QString code, QString verification) {
		*url = verification;
		File::OpenUrl(verification);
		if (const auto w = ActiveWindow()) {
			*box = w->show(Ui::MakeConfirmBox({
				.text = T("Код для входа: ", "Sign-in code: ") + code + u"\n\n"_q
					+ T("Подтвердите его в открывшемся кабинете. Окно закроется само.",
						"Confirm it in the cabinet that has opened. This window closes by itself."),
				.confirmed = [=](Fn<void()>) { File::OpenUrl(*url); },
				.cancelled = [=](Fn<void()> close) { Account::Instance().cancelLogin(); close(); },
				.confirmText = T("Открыть кабинет", "Open the cabinet"),
				.cancelText = T("Отмена", "Cancel"),
				.title = T("Аккаунт ", "Account ") + ServiceName(service),
			}));
		}
	}, [=](bool ok, QString error) {
		if (*box) {
			(*box)->closeBox();
		}
		Inform(error);
	});
}

// the code is confirmed by another program of ours: it asks the person
void SignInThrough(Service service, SignInDonor donor) {
	const auto box = std::make_shared<base::weak_qptr<Ui::GenericBox>>();
	const auto over = std::make_shared<bool>(false);
	const auto hide = [=] {
		*over = true;
		if (*box) {
			(*box)->closeBox();
		}
	};
	const auto cancel = [=](Fn<void()> close) {
		if (!*over) {
			*over = true;
			Account::Instance().cancelLogin();
		}
		close();
	};
	if (const auto w = ActiveWindow()) {
		*box = w->show(Ui::MakeConfirmBox({
			.text = T("Подтвердите вход в программе «", "Confirm the sign-in in “") + donor.title + T("».", "”."),
			.confirmed = cancel,
			.cancelled = cancel,
			.confirmText = T("Отмена", "Cancel"),
			.title = T("Аккаунт ", "Account ") + ServiceName(service),
			.inform = true,
		}));
	}
	const auto origin = ServiceOrigin(service);
	Account::Instance().startLogin(service, [=](QString code, QString) {
		crl::async([=] {
			const auto answer = SignInApprove(donor.id, code, origin);
			crl::on_main([=] {
				LOG(("Tgx: sign-in: %1").arg(answer.approved ? u"confirmed by another program"_q : u"not confirmed"_q));
				if (!answer.approved && !*over) {
					Account::Instance().cancelLogin();
					hide();
					Inform(answer.error.isEmpty() ? T("Вход не подтверждён", "The sign-in was not confirmed") : answer.error);
				}
			});
		});
	}, [=](bool ok, QString error) {
		hide();
		Inform(error);
	});
}

struct SignInOption {
	Service service = Service::Cloud;
	SignInDonor donor; // without an id — by the code
};

// Never on the main thread: asks the running programs.
std::vector<SignInOption> SignInOptions(const std::vector<Service> &services) {
	auto result = std::vector<SignInOption>();
	for (const auto service : services) {
		for (const auto &donor : SignInDonors(ServiceOrigin(service))) {
			result.push_back({ service, donor });
		}
	}
	for (const auto service : services) {
		result.push_back({ service, {} });
	}
	return result;
}

void SignInWith(const SignInOption &option) {
	if (option.donor.id.isEmpty()) {
		SignInByCode(option.service);
	} else {
		SignInThrough(option.service, option.donor);
	}
}

void ChooseSignIn(std::vector<SignInOption> list) {
	const auto window = ActiveWindow();
	if (list.size() < 2 || !window) {
		SignInWith(list.front());
		return;
	}
	auto options = std::vector<QString>();
	for (const auto &option : list) {
		const auto &donor = option.donor;
		const auto service = ServiceName(option.service);
		options.push_back(donor.id.isEmpty()
			? (T("Войти по коду: ", "Sign in with a code: ") + service)
			: ((donor.name.isEmpty()
				? (T("Войти через «", "Sign in through “") + donor.title + T("»", "”"))
				: (T("Продолжить как ", "Continue as ") + donor.name + T(" — через «", " — through “") + donor.title + T("»", "”")))
				+ (IsVice() ? (u" · "_q + service) : QString())));
	}
	window->show(Box([=](not_null<Ui::GenericBox*> box) {
		SingleChoiceBox(box, {
			.title = rpl::single(AccountTitle()),
			.options = options,
			.initialSelection = -1,
			.callback = [=](int index) {
				if (index >= 0 && index < int(list.size())) {
					SignInWith(list[index]);
				}
			},
		});
	}));
}

// Telegram does not answer and there are no bypass servers yet: one offer per start
void OfferSignIn() {
	static auto offered = false;
	const auto window = ActiveWindow();
	if (offered || !window || Account::Instance().loggedIn()) {
		return;
	}
	offered = true;
	LOG(("Tgx: offer: sign in for the bypass"));
	window->show(Ui::MakeConfirmBox({
		.text = T("Telegram не отвечает: похоже, в этой сети он замедлен или заблокирован. Войдите в аккаунт — программа включит обход сама. ",
			"Telegram does not answer: it looks slow or blocked on this network. Sign in and the bypass turns on by itself. ") + AccountTitle() + '.',
		.confirmed = [=](Fn<void()> close) { close(); AccountClicked(); },
		.confirmText = T("Войти", "Sign in"),
		.cancelText = T("Не сейчас", "Not now"),
		.title = BypassTitle(),
	}));
}

// CI only (the public test api key): no clicks on the runner
void SignInForChecks() {
	const auto mode = qEnvironmentVariable("TGX_CI_SIGNIN");
	if (mode.isEmpty() || GetBrand().apiId != kTestApiId) {
		return;
	}
	const auto wanted = qEnvironmentVariable("TGX_CI_SERVICE");
	const auto service = (wanted == u"cloud"_q)
		? Service::Cloud
		: (wanted == u"vice"_q)
		? Service::Vice
		: Services().front();
	if (mode == u"logout"_q) {
		Account::Instance().logout();
		LOG(("Tgx: account: signed out"));
		return;
	}
	crl::async([=] {
		const auto all = SignInOptions(Services());
		crl::on_main([=] {
			auto through = std::optional<SignInOption>();
			for (const auto &option : all) {
				if (option.donor.id.isEmpty()) {
					LOG(("Tgx: sign-in: code %1").arg(ServiceId(option.service)));
				} else {
					LOG(("Tgx: sign-in: donor %1 / %2 / %3 for %4").arg(option.donor.id, option.donor.title, option.donor.name, ServiceId(option.service)));
					if (option.service == service && !through) {
						through = option;
					}
				}
			}
			if (Account::Instance().loggedIn()) {
				return;
			} else if (mode == u"through"_q && through) {
				SignInWith(*through);
			} else if (mode == u"code"_q) {
				SignInByCode(service);
			}
		});
	});
}

} // namespace

void AccountClicked() {
	auto &account = Account::Instance();
	if (account.loggedIn()) {
		if (const auto w = ActiveWindow()) {
			w->show(Ui::MakeConfirmBox({
				.text = T("Выйти из аккаунта? Обход перестанет работать до следующего входа.",
					"Sign out? The bypass stops until you sign in again."),
				.confirmed = [=](Fn<void()> close) { Account::Instance().logout(); close(); },
				.confirmText = T("Выйти", "Sign out"),
			}));
		}
		return;
	}
	crl::async([] {
		auto options = SignInOptions(Services());
		crl::on_main([=] { ChooseSignIn(options); });
	});
}

QString AppsTitle() {
	return IsVice() ? u"Vice apps"_q : T("Наши приложения", "Our apps");
}

void OpenApps() {
	File::OpenUrl(u"https://cabinet.zjd.uk/app/"_q);
}

} // namespace Tgx
