"""Apply narrowly scoped Android adaptations to upstream cloudflared.

Run with the unpacked source directory and the upstream version tag, e.g.
    python tools/prepare-cloudflared.py <source-dir> 2026.10.5
The version defaults to 2026.10.5 (current as of 2026-10-29). Emits a reviewable
unified diff.
"""
import difflib
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
version = sys.argv[2] if len(sys.argv) > 2 else "2026.10.5"
patches = []

def edit(path, transform):
    file = root / path
    before = file.read_text(encoding="utf-8")
    after = transform(before)
    if before == after:
        raise RuntimeError(f"Expected upstream source not found: {path}")
    patches.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile="a/" + path, tofile="b/" + path))
    file.write_text(after, encoding="utf-8", newline="\n")

def login(source):
    anchor = 'Name:      "login",'
    assert anchor in source
    start = source.index(anchor)
    flags = source.index('Flags: []cli.Flag{', start) + len('Flags: []cli.Flag{')
    source = source[:flags] + '''
                        &cli.StringFlag{Name: sshTokenIDFlag},
                        &cli.StringFlag{Name: sshTokenSecretFlag},
                        &cli.StringSliceFlag{Name: sshHeaderFlag},''' + source[flags:]
    anchor = '\tappInfo, err := token.GetAppInfo(appURL)'
    assert anchor in source
    source = source.replace(anchor, '''
    // Quest preflight: try explicit Access credentials before fetching a user JWT.
    headers := parseRequestHeaders(c.StringSlice(sshHeaderFlag))
    if c.IsSet(sshTokenIDFlag) { headers.Set(cfAccessClientIDHeader, c.String(sshTokenIDFlag)) }
    if c.IsSet(sshTokenSecretFlag) { headers.Set(cfAccessClientSecretHeader, c.String(sshTokenSecretFlag)) }
    if len(headers) > 0 {
        req, err := http.NewRequest(http.MethodGet, appURL.String(), nil)
        if err != nil { return err }
        req.Header = headers
        q := req.URL.Query()
        q.Set("cloudflared_token_check", "true")
        req.URL.RawQuery = q.Encode()
        client := &http.Client{Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
        resp, err := client.Do(req)
        if err != nil { return err }
        resp.Body.Close()
        if resp.StatusCode >= 200 && resp.StatusCode < 500 &&
            !(resp.StatusCode >= 300 && resp.StatusCode < 400) &&
            resp.StatusCode != 401 && resp.StatusCode != 403 {
            if c.Bool(loginQuietFlag) { fmt.Fprintln(os.Stderr, "FARSIDE_AUTH_CREDENTIALS"); return nil }
        }
    }
''' + anchor, 1)
    anchor = '\tif err := verifyTokenAtEdge(appURL, appInfo, c, log); err != nil {'
    assert anchor in source
    anchor = '\tcfdToken, err := token.GetAppTokenIfExists(appInfo)'
    source = source.replace(anchor, '''
    if c.Bool(loginQuietFlag) { fmt.Fprintln(os.Stderr, "FARSIDE_AUTH_USER"); return nil }
''' + anchor, 1)
    anchor = '\toptions := &carrier.StartOptions{AppInfo: appInfo, OriginURL: appUrl.String(), Headers: headers'
    assert anchor in source
    source = source.replace(anchor, '''
    // A failed explicit-credential preflight falls back to a cached/browser user JWT.
    if c.Command.Name == "login" { headers = make(http.Header) }
''' + anchor, 1)
    return source

edit("cmd/cloudflared/access/cmd.go", login)
# Android launches the validated URL through ACTION_VIEW in the parent app.
edit("token/shell.go", lambda s: s.replace('package token', 'package token\n\nimport "errors"').replace('return getBrowserCmd(url).Start()', 'return errors.New("browser delegated to Android app")'))
edit("carrier/carrier.go", lambda s: s.replace('return Serve(conn, listener, shutdownC, options)', 'fmt.Fprintln(os.Stderr, "FARSIDE_LISTENER_READY", listener.Addr().String())\n\treturn Serve(conn, listener, shutdownC, options)'))
def token_app_domain_legacy(source):
    # 2026.5.1 flow: the auth domain arrives in the CF-Access-Domain
    # response header.
    return source.replace('return nil, errors.New("Empty app domain")', 'domain = reqURL.Hostname()')

def token_app_domain_metadata_jwt(source):
    # Newer flow: the auth domain arrives in the signed metadata JWT.
    anchor = (
        '\t// Parse auth_domain into the canonical hostname used for JWKS lookup.\n'
        '\tauthDomain, err := parseAuthDomain(unverified.AuthDomain)\n'
        '\tif err != nil {\n'
        '\t\treturn nil, fmt.Errorf("metadata JWT auth_domain validation failed: %w", err)\n'
        '\t}'
    )
    assert anchor in source, "metadata-JWT auth_domain anchor not found"
    return source.replace(anchor, (
        '\t// Parse auth_domain into the canonical hostname used for JWKS lookup.\n'
        '\t// Android adaptation: Access responses can omit the auth domain. The\n'
        '\t// original TLS-verified application hostname is the correct namespace;\n'
        '\t// an audience is still mandatory below.\n'
        '\tvar authDomain url.URL\n'
        '\tif unverified.AuthDomain == "" {\n'
        '\t\tauthDomain = url.URL{Scheme: httpsScheme, Host: reqURL.Hostname()}\n'
        '\t} else {\n'
        '\t\tauthDomain, err = parseAuthDomain(unverified.AuthDomain)\n'
        '\t\tif err != nil {\n'
        '\t\t\treturn nil, fmt.Errorf("metadata JWT auth_domain validation failed: %w", err)\n'
        '\t\t}\n'
        '\t}'
    ), 1)

# Access responses can omit the auth domain. The original TLS-verified
# application hostname is the correct cache namespace; an audience is still
# mandatory. Tries the legacy header flow first, then the metadata-JWT flow.
try:
    edit("token/token.go", token_app_domain_legacy)
except RuntimeError:
    edit("token/token.go", token_app_domain_metadata_jwt)
pathlib.Path("tools/cloudflared-android.patch").write_text("".join(patches), encoding="utf-8", newline="\n")
licenses = pathlib.Path("app/src/main/assets/licenses")
licenses.mkdir(parents=True, exist_ok=True)
(licenses / "cloudflared-LICENSE").write_text((root / "LICENSE").read_text(encoding="utf-8"), encoding="utf-8")
notices = [f"cloudflared vendored dependency licenses and notices ({version})\n"]
for file in sorted((root / "vendor").rglob("*")):
    if file.is_file() and any(word in file.name.upper() for word in ("LICENSE", "NOTICE", "COPYING")):
        notices.append(f"\n=== {file.relative_to(root).as_posix()} ===\n")
        notices.append(file.read_text(encoding="utf-8", errors="replace"))
(licenses / "cloudflared-NOTICES.txt").write_text("".join(notices), encoding="utf-8")
