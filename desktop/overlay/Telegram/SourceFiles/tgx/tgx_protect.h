/*
Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN, based on Telegram Desktop.
Licensed under GNU GPL v3 with the OpenSSL exception, like Telegram Desktop.
*/
#pragma once

#include <QtCore/QByteArray>

namespace Tgx {

// Windows DPAPI: only this Windows account on this computer can read the value. Empty — it did not work (or not Windows).
[[nodiscard]] QByteArray Protect(const QByteArray &value);
[[nodiscard]] QByteArray Unprotect(const QByteArray &value);

} // namespace Tgx
