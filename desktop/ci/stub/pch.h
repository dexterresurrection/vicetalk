// Stand-in for Telegram Desktop's precompiled header: enough to compile tgx_signin.cpp alone in minutes
// (the whole client takes hours on a runner).
#pragma once
#include <QtCore/QString>
#include <algorithm>
#include <vector>
inline void StubLog(const QString &) {
}
#define LOG(msg) (StubLog(QString msg))
inline QString operator""_q(const char16_t *s, size_t n) {
	return QString::fromUtf16(s, qsizetype(n));
}
template <typename Signature> class Fn;
