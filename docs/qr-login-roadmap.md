# QR login roadmap

QR login is planned as a cross-device authorization flow similar to Steam sign-in. It must not
encode a session, access token or reusable credential in the QR image.

## Protocol

1. The browser creates a short-lived login transaction and displays a random public transaction ID.
2. The mobile ODS account scans the QR code over an authenticated app session.
3. The mobile app displays the requesting browser, relying party, approximate location and scopes.
4. The user approves with a passkey or device biometric.
5. The backend atomically changes the transaction from `pending` to `approved`.
6. The browser receives a one-time authorization result through polling or SSE and exchanges it for
   its normal ODS session.

## Security requirements

- 60–120 second expiry;
- single-use transaction and compare-and-set state transition;
- QR contains only an opaque random identifier and canonical ODS URL;
- approval requires a fresh mobile authentication factor;
- browser and mobile device binding;
- explicit anti-phishing confirmation screen;
- rate limiting and replay detection;
- audit events for create, scan, approve, deny, expire and consume;
- no approval from a screenshot after expiry;
- ability to revoke mobile devices and QR login from the personal account.

## Account controls

The personal account will list password, TOTP, passkeys, trusted devices, QR login and active
sessions. Users can enable or disable each permitted method, subject to organization policy and the
rule that at least one recovery-capable method remains.

This document is a delivery contract, not a claim that QR login is already implemented.
