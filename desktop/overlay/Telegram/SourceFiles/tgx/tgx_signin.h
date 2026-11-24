/*
Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN, based on Telegram Desktop.
Licensed under GNU GPL v3 with the OpenSSL exception, like Telegram Desktop.
*/
#pragma once

#include <QtCore/QString>
#include <vector>

// Sign-in through another program of ours on this computer (only the asking side). No tokens travel between the programs:
// we take an ordinary device code from our cabinet, the other program asks the person and confirms that code with its own
// session, and our usual poll gets our own session. No keys are asked for.
namespace Tgx {

// Accounts of the two services are separate: a sign-in to one means nothing for the other.
inline constexpr auto kAccountCloud = "https://cabinet.zjd.uk";
inline constexpr auto kAccountCloudMirror = "https://cabinet.0nn.us";
inline constexpr auto kAccountVice = "https://vice.zjd.uk";

struct SignInDonor {
	QString id;
	QString title; // what the program calls itself
	QString name; // the account's name in the cabinet
};

struct SignInAnswer {
	bool approved = false;
	QString error; // empty and not approved — the person refused
};

// Blocking calls, never on the main thread.
[[nodiscard]] std::vector<SignInDonor> SignInDonors(const QString &origin);
[[nodiscard]] SignInAnswer SignInApprove(const QString &id, const QString &code, const QString &origin);

} // namespace Tgx
