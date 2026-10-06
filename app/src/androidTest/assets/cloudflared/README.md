The localhost PKCS12 key and PEM certificate are public, self-signed test fixtures.
Password: fixture-password. They ship only in the instrumentation APK and protect no
real service. The fixture CA is trusted only by test child processes through a private
temporary SSL_CERT_FILE; Android and production cloudflared trust remain unchanged.
