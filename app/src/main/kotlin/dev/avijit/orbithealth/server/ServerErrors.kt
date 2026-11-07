package dev.avijit.orbithealth.server

import java.io.IOException

/** Typed failures talking to the paired Orbit Server; messages never contain tokens or health values. */
sealed class ServerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The server presented a certificate other than the one pinned at pairing. Nothing was sent. UI: "Server identity
 * changed — re-pair". [actual] is the presented leaf's fingerprint, if there was one.
 */
class ServerIdentityChanged(val expected: String, val actual: String?) : ServerException("server identity changed")

/** The link is not a valid `orbit://pair?...` pairing link. */
class InvalidPairingLink : ServerException("not an Orbit Server pairing link")

/** The pairing link was refused (used, expired or unknown): make a new one on the server. */
class PairingRejected : ServerException("pairing link refused")

/** The server no longer accepts this phone's device token (revoked in the dashboard): pair again. */
class ServerUnauthorized : ServerException("device token rejected")

/** `/v1/agent/ask` 409: no model is configured; choose one in the server dashboard. */
class NoModel : ServerException("no model configured on the server")

/** `/v1/agent/ask` 502: the server's model provider failed. */
class ModelError : ServerException("the server's model failed")

/** `/v1/agent/ask` 413: the question plus its history is larger than the server accepts; start a new conversation. */
class AskTooLarge : ServerException("question and history too large")

/** 429: too many requests; try again shortly. */
class RateLimited : ServerException("rate limited")

/** No paired server. */
class NotPaired : ServerException("no Orbit Server paired")

/** Any other HTTP failure; [code] is the server's `{"error": code}` when it sent one. */
class ServerHttpError(val status: Int, val code: String?) : ServerException("HTTP $status${code?.let { " ($it)" } ?: ""}")

/** The server could not be reached or the connection failed (DNS, refused, timeout, TLS other than the pin). */
class ServerUnreachable(cause: Throwable) : ServerException("server unreachable: ${cause.javaClass.simpleName}", cause)
