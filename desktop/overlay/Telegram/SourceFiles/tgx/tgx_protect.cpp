/*
Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN, based on Telegram Desktop.
Licensed under GNU GPL v3 with the OpenSSL exception, like Telegram Desktop.
*/
#include "tgx/tgx_protect.h"

#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif // NOMINMAX
#include <windows.h>
#include <wincrypt.h>
#include <dpapi.h>
#pragma comment(lib, "crypt32.lib")
#endif // Q_OS_WIN

namespace Tgx {
#ifdef Q_OS_WIN
namespace {

// not a secret: keeps the value apart from other programs' DPAPI data
constexpr char kEntropy[] = "CloudTalk account 1";

template <typename Method>
[[nodiscard]] QByteArray Crypt(const QByteArray &value, Method method) {
	if (value.isEmpty()) {
		return {};
	}
	auto in = DATA_BLOB{ DWORD(value.size()), reinterpret_cast<BYTE*>(const_cast<char*>(value.constData())) };
	auto entropy = DATA_BLOB{ DWORD(sizeof(kEntropy) - 1), reinterpret_cast<BYTE*>(const_cast<char*>(kEntropy)) };
	auto out = DATA_BLOB();
	if (!method(&in, &entropy, &out)) {
		return {};
	}
	auto result = QByteArray(reinterpret_cast<const char*>(out.pbData), qsizetype(out.cbData));
	SecureZeroMemory(out.pbData, out.cbData);
	LocalFree(out.pbData);
	return result;
}

} // namespace
#endif // Q_OS_WIN

QByteArray Protect(const QByteArray &value) {
#ifdef Q_OS_WIN
	return Crypt(value, [](DATA_BLOB *in, DATA_BLOB *entropy, DATA_BLOB *out) {
		return CryptProtectData(in, L"Cloud Talk", entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, out);
	});
#else // Q_OS_WIN
	return {};
#endif // Q_OS_WIN
}

QByteArray Unprotect(const QByteArray &value) {
#ifdef Q_OS_WIN
	return Crypt(value, [](DATA_BLOB *in, DATA_BLOB *entropy, DATA_BLOB *out) {
		return CryptUnprotectData(in, nullptr, entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, out);
	});
#else // Q_OS_WIN
	return {};
#endif // Q_OS_WIN
}

} // namespace Tgx
