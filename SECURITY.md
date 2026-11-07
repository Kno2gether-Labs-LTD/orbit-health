# Security policy

Orbit Health holds health data, so security reports matter a lot to us.

## Reporting a vulnerability
Please report privately through GitHub: **Security → Report a vulnerability** on this repository. Don't open a public issue for security problems.

Include what you found, how to reproduce it, and the app or server version. We'll acknowledge your report and keep you updated while we fix it.

## Design notes
- Health data is stored only on the phone, in an encrypted database (SQLCipher, key held by Android Keystore).
- No analytics, ads or tracking SDKs. The app goes online only to an Orbit Server you pair yourself, over HTTPS with a pinned certificate, and every request appears in the app's network log.
- Android backup and device-to-device transfer are disabled for the app's data.
- Orbit Server binds to loopback by default; pairing uses one-time codes, and agent access is scoped and logged.
- Ring history is never deleted from the ring while the vendor app is installed, and debug tools never send delete commands.
