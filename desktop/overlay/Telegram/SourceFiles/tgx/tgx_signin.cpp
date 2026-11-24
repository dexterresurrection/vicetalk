/*
Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN, based on Telegram Desktop.
Licensed under GNU GPL v3 with the OpenSSL exception, like Telegram Desktop.
*/
#include "tgx/tgx_signin.h"

#include "tgx/tgx.h"

#include <QtCore/QDeadlineTimer>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <QtCore/QRegularExpression>

#include <optional>
#include <string>

#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif // NOMINMAX
#include <windows.h>
#include <sddl.h>
#include <shlobj.h>
#include <knownfolders.h>
#endif // Q_OS_WIN

namespace Tgx {
#ifdef Q_OS_WIN
namespace {

// Which account an address belongs to; an unknown address equals nothing, not even itself.
[[nodiscard]] QString AccountOf(const QString &origin) {
	if (origin == QString::fromLatin1(kAccountCloud) || origin == QString::fromLatin1(kAccountCloudMirror)) {
		return u"cloud"_q;
	}
	return (origin == QString::fromLatin1(kAccountVice)) ? u"vice"_q : QString();
}

// The same for the field of an answer: a program older than the field knows only the first service.
[[nodiscard]] QString AccountOfAnswer(const QJsonObject &answer) {
	const auto origin = answer.value(u"origin"_q).toString();
	return AccountOf(origin.isEmpty() ? QString::fromLatin1(kAccountCloud) : origin);
}

[[nodiscard]] bool Same(const QString &a, const QString &b) {
	return !a.isEmpty() && (a == b);
}

[[nodiscard]] QString Clean(QString text) {
	static const auto bad = QRegularExpression(u"[\\p{Cc}\\p{Cf}]"_q);
	return text.replace(bad, u" "_q).trimmed().left(60);
}

struct App {
	const char *id = nullptr;
	const wchar_t *key = nullptr; // HKLM\…\Uninstall\<key>, written by the installer with administrator rights
	const wchar_t *exe = nullptr;
};

constexpr App kApps[] = {
	{ "cloudvpn", L"CloudVPN", L"CloudVPN.exe" },
	{ "messenger", L"CloudMessenger", L"CloudMessenger.exe" },
	{ "social", L"CloudSocial", L"CloudSocial.exe" },
	{ "wave", L"ViceWave", L"ViceWave.exe" },
	{ "mail", L"CloudMail", L"CloudMail.exe" },
	{ "wallet", L"CloudWallet", L"CloudWallet.exe" },
	{ "torrent", L"CloudTorrent", L"CloudTorrent.exe" },
};

class Handle final {
public:
	explicit Handle(HANDLE value) : _value(value) {
	}
	Handle(const Handle &) = delete;
	Handle &operator=(const Handle &) = delete;
	~Handle() {
		if (valid()) {
			CloseHandle(_value);
		}
	}
	[[nodiscard]] bool valid() const {
		return _value && (_value != INVALID_HANDLE_VALUE);
	}
	[[nodiscard]] HANDLE get() const {
		return _value;
	}

private:
	HANDLE _value = nullptr;

};

[[nodiscard]] std::wstring FullPath(const std::wstring &path) {
	auto buffer = std::vector<wchar_t>(32768);
	const auto n = GetFullPathNameW(path.c_str(), DWORD(buffer.size()), buffer.data(), nullptr);
	return (n > 0 && n < buffer.size()) ? std::wstring(buffer.data(), n) : std::wstring();
}

[[nodiscard]] bool SamePath(const std::wstring &a, const std::wstring &b) {
	return CompareStringOrdinal(a.c_str(), int(a.size()), b.c_str(), int(b.size()), TRUE) == CSTR_EQUAL;
}

// Where the installer put the program: only HKLM and only inside Program Files (elsewhere any process of the person could
// have replaced the file).
[[nodiscard]] std::wstring ExeOf(const App &app) {
	const auto path = std::wstring(L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\") + app.key;
	auto key = HKEY();
	if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, path.c_str(), 0, KEY_QUERY_VALUE | KEY_WOW64_64KEY, &key) != ERROR_SUCCESS) {
		return {};
	}
	auto buffer = std::vector<wchar_t>(4096);
	auto type = DWORD();
	auto bytes = DWORD((buffer.size() - 1) * sizeof(wchar_t));
	const auto result = RegQueryValueExW(key, L"InstallLocation", nullptr, &type, reinterpret_cast<BYTE*>(buffer.data()), &bytes);
	RegCloseKey(key);
	if (result != ERROR_SUCCESS || type != REG_SZ || !buffer[0]) {
		return {};
	}
	const auto exe = FullPath(std::wstring(buffer.data()) + L"\\" + app.exe);
	for (const auto &id : { FOLDERID_ProgramFiles, FOLDERID_ProgramFilesX64, FOLDERID_ProgramFilesX86 }) {
		auto folder = PWSTR();
		if (SHGetKnownFolderPath(id, 0, nullptr, &folder) == S_OK) {
			const auto root = FullPath(folder) + L"\\";
			CoTaskMemFree(folder);
			if (root.size() > 1 && exe.size() > root.size() && SamePath(exe.substr(0, root.size()), root)) {
				return exe;
			}
		}
	}
	return {};
}

[[nodiscard]] bool ProcessIs(DWORD pid, const App &app) {
	const auto process = Handle(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid));
	if (!process.valid()) {
		return false;
	}
	auto buffer = std::vector<wchar_t>(32768);
	auto size = DWORD(buffer.size());
	if (!QueryFullProcessImageNameW(process.get(), 0, buffer.data(), &size)) {
		return false;
	}
	const auto expected = ExeOf(app);
	return !expected.empty() && SamePath(FullPath(std::wstring(buffer.data(), size)), expected);
}

[[nodiscard]] std::wstring PipeName(const App &app) {
	auto token = HANDLE();
	if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) {
		return {};
	}
	const auto guard = Handle(token);
	auto buffer = std::vector<BYTE>(1024);
	auto size = DWORD();
	if (!GetTokenInformation(token, TokenUser, buffer.data(), DWORD(buffer.size()), &size)) {
		return {};
	}
	auto sid = LPWSTR();
	if (!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &sid)) {
		return {};
	}
	const auto result = L"\\\\.\\pipe\\uk.zjd.signin." + QString::fromLatin1(app.id).toStdWString() + L"." + sid;
	LocalFree(sid);
	return result;
}

// Overlapped read or write that never hangs: a silent other side is cut off at the deadline.
[[nodiscard]] bool Transfer(HANDLE pipe, bool write, void *data, DWORD size, DWORD *done, int timeout) {
	const auto event = Handle(CreateEventW(nullptr, TRUE, FALSE, nullptr));
	if (!event.valid()) {
		return false;
	}
	auto overlapped = OVERLAPPED();
	overlapped.hEvent = event.get();
	const auto started = write
		? WriteFile(pipe, data, size, nullptr, &overlapped)
		: ReadFile(pipe, data, size, nullptr, &overlapped);
	if (!started && GetLastError() != ERROR_IO_PENDING) {
		return false;
	}
	if (WaitForSingleObject(event.get(), DWORD(timeout > 0 ? timeout : 0)) != WAIT_OBJECT_0) {
		CancelIoEx(pipe, &overlapped);
	}
	return GetOverlappedResult(pipe, &overlapped, done, TRUE) && (*done > 0);
}

[[nodiscard]] std::optional<QJsonObject> Call(const App &app, const QJsonObject &request, int timeout, QString *error) {
	const auto name = PipeName(app);
	const auto fail = [&](const char *ru, const char *en) {
		if (error) {
			*error = T(ru, en);
		}
		return std::optional<QJsonObject>();
	};
	if (name.empty() || ExeOf(app).empty()) {
		return fail("Та программа не установлена", "That program is not installed");
	}
	auto raw = INVALID_HANDLE_VALUE;
	for (const auto until = QDeadlineTimer(2000);;) {
		// SECURITY_IDENTIFICATION: the other side cannot act on our behalf
		raw = CreateFileW(name.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
			FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION, nullptr);
		if (raw != INVALID_HANDLE_VALUE) {
			break;
		}
		const auto code = GetLastError();
		if (code == ERROR_FILE_NOT_FOUND) {
			return fail("Та программа не запущена", "That program is not running");
		} else if (code != ERROR_PIPE_BUSY) {
			return fail("Та программа не приняла запрос", "That program did not accept the request");
		} else if (until.hasExpired()) {
			return fail("Та программа занята — попробуйте ещё раз", "That program is busy, try again");
		}
		Sleep(150);
	}
	const auto pipe = Handle(raw);
	auto pid = ULONG();
	if (!GetNamedPipeServerProcessId(pipe.get(), &pid) || !ProcessIs(pid, app)) {
		LOG(("Tgx: sign-in: not our program behind the pipe of %1").arg(QString::fromLatin1(app.id)));
		return fail("На канале не наша программа", "It is not our program");
	}
	auto line = QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n';
	auto done = DWORD();
	if (!Transfer(pipe.get(), true, line.data(), DWORD(line.size()), &done, 3000) || done != DWORD(line.size())) {
		return fail("Та программа не приняла запрос", "That program did not accept the request");
	}
	auto answer = QByteArray();
	const auto until = QDeadlineTimer(timeout);
	while (!answer.contains('\n') && answer.size() < 8192) {
		char buffer[1024];
		if (!Transfer(pipe.get(), false, buffer, sizeof(buffer), &done, int(until.remainingTime()))) {
			break;
		}
		answer.append(buffer, int(done));
	}
	const auto end = answer.indexOf('\n');
	if (end < 0) {
		return fail("Та программа не ответила", "That program did not answer");
	}
	const auto parsed = QJsonDocument::fromJson(answer.left(end));
	return parsed.isObject()
		? std::make_optional(parsed.object())
		: fail("Та программа ответила непонятно", "That program gave an unclear answer");
}

[[nodiscard]] std::optional<QJsonObject> State(const App &app, const QString &origin) {
	const auto answer = Call(app, { { u"v"_q, 1 }, { u"op"_q, u"state"_q }, { u"origin"_q, origin } }, 3000, nullptr);
	return (answer
		&& answer->value(u"ok"_q).toBool()
		&& answer->value(u"signed_in"_q).toBool()
		&& Same(AccountOfAnswer(*answer), AccountOf(origin)))
		? answer
		: std::nullopt;
}

} // namespace
#endif // Q_OS_WIN

std::vector<SignInDonor> SignInDonors(const QString &origin) {
	auto result = std::vector<SignInDonor>();
#ifdef Q_OS_WIN
	for (const auto &app : kApps) {
		if (const auto answer = State(app, origin)) {
			result.push_back({
				.id = QString::fromLatin1(app.id),
				.title = Clean(answer->value(u"title"_q).toString()),
				.name = Clean(answer->value(u"name"_q).toString()),
			});
		}
	}
#endif // Q_OS_WIN
	LOG(("Tgx: sign-in: %1 program(s) can confirm").arg(result.size()));
	return result;
}

SignInAnswer SignInApprove(const QString &id, const QString &code, const QString &origin) {
#ifdef Q_OS_WIN
	for (const auto &app : kApps) {
		if (id != QString::fromLatin1(app.id)) {
			continue;
		}
		// asked again right before: it could have been replaced or signed out meanwhile, and a program older than the
		// «origin» field would confirm any code with its own service
		if (!State(app, origin)) {
			return { .error = T("В той программе вход уже кончился", "That program is not signed in any more") };
		}
		auto error = QString();
		const auto answer = Call(app, { { u"v"_q, 1 }, { u"op"_q, u"approve"_q }, { u"code"_q, code }, { u"origin"_q, origin } }, 120000, &error);
		if (!answer) {
			return { .error = error };
		} else if (answer->value(u"no_account"_q).toBool()) {
			return { .error = T("В той программе вход уже кончился", "That program is not signed in any more") };
		} else if (const auto text = Clean(answer->value(u"error"_q).toString()); !text.isEmpty()) {
			return { .error = text };
		} else if (!answer->value(u"ok"_q).toBool()) {
			return {}; // the person refused
		} else if (!Same(AccountOfAnswer(*answer), AccountOf(origin))) {
			return { .error = T("В той программе вход в другой аккаунт", "That program is signed in to another account") };
		}
		return { .approved = true };
	}
#endif // Q_OS_WIN
	return { .error = T("Нет такой программы", "No such program") };
}

} // namespace Tgx
