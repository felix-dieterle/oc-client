# Keystore

This directory contains the signing keystore used for all debug builds.

## Why the keystore is in the repository

To ensure users can install APK updates over previous versions without
uninstalling, every build must be signed with the **same key**. This keystore
is intentionally stored here so that any CI run (and any fork) produces
consistently-signed APKs.

## Credentials

| Field            | Value        |
|------------------|--------------|
| Keystore file    | `release.jks`|
| Keystore password| `occlient123`|
| Key alias        | `oc-client`  |
| Key password     | `occlient123`|

These defaults are used automatically when the `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
and `KEY_PASSWORD` environment variables are not set. For production or private
forks, set those variables as GitHub repository secrets to use custom credentials.

## Regenerating the keystore

```bash
keytool -genkeypair \
  -alias oc-client \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore keystore/release.jks \
  -storepass occlient123 \
  -keypass occlient123 \
  -dname "CN=OC Client, OU=Android, O=felix-dieterle, L=Unknown, S=Unknown, C=DE"
```
