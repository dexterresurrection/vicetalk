# Cloud Talk / Vice Talk on a real Windows (GitHub runner), from the installers in setup-url.txt / vice-url.txt:
#  1. Cloud installer /S: files, brand.json (cloud), Start menu shortcut;
#  2. Telegram blocked for CloudTalk.exe by Windows Firewall (as from Russia), our test bypass server (VLESS + Reality)
#     on the runner, talk.json with Telegram Desktop's public development keys and that server;
#  3. CloudTalk.exe: direct attempt fails → bypass turns on by itself → connected through xray; screenshot;
#  4. Vice installer over it: own folder, brand vice, Cloud removed, window title «Vice Talk», connects too.
$ErrorActionPreference = 'Continue'
$R = "$PWD\results"; New-Item -ItemType Directory -Force $R | Out-Null
$SUM = "$R\summary.txt"; '' | Out-File $SUM -Encoding utf8
$script:fails = 0
function ok($t)   { "OK   $t" | Tee-Object -FilePath $SUM -Append }
function bad($t)  { "FAIL $t" | Tee-Object -FilePath $SUM -Append; $script:fails++ }
function note($t) { "--   $t" | Tee-Object -FilePath $SUM -Append }
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
function shot($name) {
  try {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
    $bmp.Save("$R\$name.png"); $g.Dispose(); $bmp.Dispose()
  } catch { note "screenshot ${name}: $_" }
}
function fetch($urlFile, $shaFile, $out) {
  $u = (Get-Content $urlFile -First 1).Trim(); $s = (Get-Content $shaFile -First 1).Trim()
  Invoke-WebRequest $u -OutFile $out -UseBasicParsing
  if ((Get-FileHash $out -Algorithm SHA256).Hash.ToLower() -ne $s) { bad "sha256 of $out"; return $false }
  return $true
}

# ---------- test bypass server under its own exe path (not blocked)
New-Item -ItemType Directory -Force C:\xrsrv | Out-Null
Invoke-WebRequest "https://github.com/XTLS/Xray-core/releases/download/v26.11.9/Xray-windows-64.zip" -OutFile C:\xrsrv\x.zip -UseBasicParsing
Expand-Archive C:\xrsrv\x.zip C:\xrsrv -Force
$keys = & C:\xrsrv\xray.exe x25519
$priv = (($keys | Select-String 'PrivateKey') -split ': ')[1].Trim()
$pub = (($keys | Select-String 'Password|PublicKey' | Select-Object -First 1) -split ': ')[1].Trim()
$uuid = (& C:\xrsrv\xray.exe uuid).Trim()
$sid = -join ((1..8) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })
@"
{"log":{"loglevel":"warning","access":"C:/xrsrv/access.log"},
 "inbounds":[{"listen":"127.0.0.1","port":7443,"protocol":"vless","settings":{"clients":[{"id":"$uuid","flow":"xtls-rprx-vision"}],"decryption":"none"},
   "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"dest":"www.microsoft.com:443","serverNames":["www.microsoft.com"],"privateKey":"$priv","shortIds":["$sid"]}}}],
 "outbounds":[{"protocol":"freedom"}]}
"@ | Out-File C:\xrsrv\server.json -Encoding ascii
Start-Process C:\xrsrv\xray.exe -ArgumentList 'run','-c','C:\xrsrv\server.json' -WindowStyle Hidden
Start-Sleep 2
$outb = "{`"protocol`":`"vless`",`"settings`":{`"vnext`":[{`"address`":`"127.0.0.1`",`"port`":7443,`"users`":[{`"id`":`"$uuid`",`"flow`":`"xtls-rprx-vision`",`"encryption`":`"none`"}]}]},`"streamSettings`":{`"network`":`"tcp`",`"security`":`"reality`",`"realitySettings`":{`"serverName`":`"www.microsoft.com`",`"fingerprint`":`"chrome`",`"publicKey`":`"$pub`",`"shortId`":`"$sid`"}}}"
# Telegram Desktop's public development keys (TDESKTOP_API_TEST) — CI only, never in a published build
$talkcfg = "{`"api_id`":17349,`"api_hash`":`"344583e45741c457fe1862106095a5eb`",`"version`":`"0.1.0`",`"bypass`":{`"mode`":`"auto`",`"xray`":[$outb],`"mtproto`":[]}}"
$tgNets = '149.154.160.0/20','91.108.4.0/22','91.108.8.0/22','91.108.12.0/22','91.108.16.0/22','91.108.20.0/22','91.108.56.0/22','95.161.64.0/20','185.76.151.0/24','2001:67c:4e8::/48','2001:b28:f23d::/48','2001:b28:f23f::/48'

# an older exe keeps its data next to itself for an administrator: the check can send it to AppData explicitly
function start-talk($exe) {
  if (Test-Path "$PSScriptRoot\force-workdir.txt") { return (Start-Process $exe -ArgumentList '-workdir', "`"$env:APPDATA\Cloud Talk`"" -PassThru) }
  return (Start-Process $exe -PassThru)
}
function run-line($dir, $line, $name) {
  $exe = "$dir\CloudTalk.exe"
  New-NetFirewallRule -DisplayName "talk-block-$line" -Direction Outbound -Program $exe -RemoteAddress $tgNets -Action Block | Out-Null
  Set-NetFirewallProfile -All -Enabled True
  $talkcfg | Out-File "$dir\talk.json" -Encoding ascii
  $data = "$env:APPDATA\Cloud Talk"
  Remove-Item "$data\log.txt", "$data\tdata\tgx\bypass.json" -ErrorAction SilentlyContinue   # direct first, nothing remembered
  New-Item -ItemType Directory -Force "$env:APPDATA\Cloud Talk" | Out-Null
  $p = start-talk $exe
  $log = "$data\log.txt"
  $deadline = (Get-Date).AddSeconds(150)
  $seen = @{}
  while ((Get-Date) -lt $deadline) {
    Start-Sleep 3
    if (Test-Path $log) {
      $t = Get-Content $log -Raw -ErrorAction SilentlyContinue
      foreach ($k in 'Tgx: brand','enable: auto','xray up on','telegram state 2 (bypass)') { if ($t -match [regex]::Escape($k)) { $seen[$k] = $true } }
      if ($seen['telegram state 2 (bypass)']) { break }
    }
  }
  Start-Sleep 4
  shot "$line-window"
  $t = if (Test-Path $log) { Get-Content $log -Raw } else { '' }
  ($t -split "`n" | Select-String 'Tgx:' | Select-Object -Last 40) | Out-File "$R\$line-tgx.txt" -Encoding utf8
  Copy-Item $log "$R\$line-log.txt" -ErrorAction SilentlyContinue
  $b = ($t -split "`n" | Select-String 'Tgx: brand' | Select-Object -First 1)
  if ($b -and "$b" -match "brand $line, api set") { ok "${name}: brand $line, api keys from talk.json" } else { bad "${name}: brand line '$b'" }
  if ($seen['enable: auto']) { ok "${name}: direct failed, bypass turned on by itself" } else { bad "${name}: bypass did not turn on" }
  if ($seen['xray up on']) { ok "${name}: xray core running" } else { bad "${name}: xray core did not start" }
  if ($seen['telegram state 2 (bypass)']) { ok "${name}: Telegram connected through the bypass" } else { bad "${name}: no connection through the bypass" }
  if (Get-Process xray -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$dir*" }) { ok "${name}: xray.exe child alive" } else { bad "${name}: no xray.exe from $dir" }
  if (Test-Path "$dir\tdata") { bad "${name}: data next to the program, not in AppData" } elseif (Test-Path "$data\tdata") { ok "${name}: data in AppData" } else { bad "${name}: no data folder" }
  $title = (Get-Process CloudTalk -ErrorAction SilentlyContinue | Select-Object -First 1).MainWindowTitle
  if ($title -like "*$name*") { ok "${name}: window title '$title'" } else { note "${name}: window title '$title'" }
  Stop-Process -Name CloudTalk -Force -ErrorAction SilentlyContinue
  Start-Sleep 3
  if (Get-Process xray -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$dir*" }) { bad "${name}: xray.exe survived the client (job object)" } else { ok "${name}: xray.exe ended with the client" }
  Remove-NetFirewallRule -DisplayName "talk-block-$line" -ErrorAction SilentlyContinue
}

# ---------- shared sign-in (only the asking side): a fake cabinet, test donors in Program Files as our programs are
$tgx = "$env:APPDATA\Cloud Talk\tdata\tgx"
$cab = "$R\cabinet.log"
New-Item -ItemType Directory -Force C:\cab, C:\donor, C:\donorfake | Out-Null
"{`"xray`":[$outb],`"mtproto`":[]}" | Out-File C:\cab\talk-profile.json -Encoding ascii
$null = Start-Process python -ArgumentList "$PWD\..\..\..\android\ci\signin\fake_cabinet.py",'8081','C:\cab\talk-profile.json',$cab -PassThru -WindowStyle Hidden
& C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /out:C:\donor\donor.exe "$PWD\donor.cs" | Out-String | Write-Host
if (-not (Test-Path C:\donor\donor.exe)) { bad 'sign-in: the test donor did not compile' }
function place-donor($key, $folder, $exe) {
  New-Item -ItemType Directory -Force $folder | Out-Null
  Copy-Item C:\donor\donor.exe "$folder\$exe" -Force
  $k = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$key"
  New-Item -Path $k -Force | Out-Null
  Set-ItemProperty -Path $k -Name InstallLocation -Value $folder
}
function start-donors($mode) {
  stop-donors
  Remove-Item C:\donor\*.log -ErrorAction SilentlyContinue
  Start-Process 'C:\Program Files\CI VPN\CloudVPN.exe' -ArgumentList 'cloudvpn','https://cabinet.zjd.uk','"CI VPN"','"CI Anna"',$mode,'C:\donor\cloudvpn.log'
  Start-Process 'C:\Program Files\CI Mail\CloudMail.exe' -ArgumentList 'mail','-','"CI Old"','"CI Anna"',$mode,'C:\donor\mail.log'
  Start-Process 'C:\Program Files\CI Wave\ViceWave.exe' -ArgumentList 'wave','https://vice.zjd.uk','"CI Wave"','"CI Vera"',$mode,'C:\donor\wave.log'
  # the pipe of the messenger is taken by a copy outside Program Files: not our program
  Start-Process 'C:\donorfake\CloudMessenger.exe' -ArgumentList 'messenger','https://cabinet.zjd.uk','"CI Fake"','"CI Mallory"',$mode,'C:\donor\messenger.log'
  Start-Sleep 2
}
function stop-donors { Stop-Process -Name CloudVPN, CloudMail, ViceWave, CloudMessenger -Force -ErrorAction SilentlyContinue; Start-Sleep 1 }
function cab-count($re) { (Select-String -Path $cab -Pattern $re -ErrorAction SilentlyContinue | Measure-Object).Count }
place-donor 'CloudVPN' 'C:\Program Files\CI VPN' 'CloudVPN.exe'
place-donor 'CloudMail' 'C:\Program Files\CI Mail' 'CloudMail.exe'
place-donor 'ViceWave' 'C:\Program Files\CI Wave' 'ViceWave.exe'
place-donor 'CloudMessenger' 'C:\Program Files\CI Messenger' 'CloudMessenger.exe'
Copy-Item C:\donor\donor.exe C:\donorfake\CloudMessenger.exe -Force

# Invoke-RestMethod gives a JSON array as one object: unrolled through a variable
function pending-codes {
  $x = Invoke-RestMethod http://127.0.0.1:8081/pending -ErrorAction SilentlyContinue
  return @($x | ForEach-Object { $_ } | Where-Object { $_ } | ForEach-Object { "$_" })
}
# starts the client with the given TGX_CI_SIGNIN until $until is in its log; returns the log
function run-signin($dir, $mode, $until, $seconds, $keep = $false) {
  $cfg = "{`"api_id`":17349,`"api_hash`":`"344583e45741c457fe1862106095a5eb`",`"version`":`"0.1.0`",`"test_origin`":`"http://127.0.0.1:8081`",`"bypass`":{`"mode`":`"auto`"}}"
  $cfg | Out-File "$dir\talk.json" -Encoding ascii
  if (-not $keep) { Remove-Item "$tgx\account*.json", "$tgx\profiles*.json", "$tgx\bypass.json" -ErrorAction SilentlyContinue }
  $log = "$env:APPDATA\Cloud Talk\log.txt"
  Remove-Item $log -ErrorAction SilentlyContinue
  $before = pending-codes   # codes of refused sign-ins stay there
  $env:TGX_CI_SIGNIN = $mode
  $null = start-talk "$dir\CloudTalk.exe"
  $env:TGX_CI_SIGNIN = ''
  $deadline = (Get-Date).AddSeconds($seconds); $t = ''; $approved = $false
  while ((Get-Date) -lt $deadline) {
    Start-Sleep 2
    $t = Get-Content $log -Raw -ErrorAction SilentlyContinue
    if ($mode -eq 'code' -and -not $approved) {   # «confirmed in the cabinet»
      $pending = @(pending-codes | Where-Object { $before -notcontains $_ })
      if ($pending.Count -gt 0) {
        Invoke-RestMethod http://127.0.0.1:8081/api/cabinet/auth/device/approve -Method Post -Headers @{ Authorization = 'Bearer donor' } -Body (@{ user_code = "$($pending[-1])" } | ConvertTo-Json) | Out-Null
        $approved = $true
      }
    }
    if ($t -match $until) { Start-Sleep 3; break }
  }
  $script:sshot++
  shot "signin-$($script:sshot)"
  $t = Get-Content $log -Raw -ErrorAction SilentlyContinue
  Stop-Process -Name CloudTalk -Force -ErrorAction SilentlyContinue
  Start-Sleep 2
  (($t -split "`n") | Select-String 'Tgx:') | Out-File "$R\signin-$($script:sshot).txt" -Encoding utf8
  return "$t"
}
$script:sshot = 0
function check-signin($dir, $line) {
  $vice = ($line -eq 'vice')
  $first = if ($vice) { 'donor wave / CI Wave / CI Vera' } else { 'donor cloudvpn / CI VPN / CI Anna' }
  New-NetFirewallRule -DisplayName "talk-block-signin" -Direction Outbound -Program "$dir\CloudTalk.exe" -RemoteAddress $tgNets -Action Block | Out-Null
  stop-donors
  $t = run-signin $dir 'code' 'telegram state 2 \(bypass\)' 120
  if ($t -match 'sign-in: 0 program') { ok "${line}: no donors — nothing offered" } else { bad "${line}: no donors" }
  if ($t -match 'offer: sign in for the bypass') { ok "${line}: Telegram blocked and no servers — the sign-in is offered" } else { bad "${line}: no offer of the sign-in" }
  if ($t -match 'sign-in: done') { ok "${line}: sign-in by the device code" } else { bad "${line}: sign-in by the device code" }
  if ($t -match 'profiles from the account: 1 xray') { ok "${line}: bypass servers of the account arrived" } else { bad "${line}: no bypass servers" }
  if ($t -match 'telegram state 2 \(bypass\)') { ok "${line}: Telegram connected through the account's bypass" } else { bad "${line}: no connection through the account's bypass" }
  if ((cab-count 'refresh ok rotate=True') -ge 1 -and (cab-count 'refresh 401') -eq 0) { ok "${line}: session renewed with rotate" } else { bad "${line}: refresh $(cab-count 'refresh ok rotate=True') ok, $(cab-count 'refresh 401') refused" }
  start-donors 'deny'
  $done = cab-count 'poll: signed in'
  $t = run-signin $dir 'through' 'sign-in: not confirmed' 60
  if ($t -match [regex]::Escape($first)) { ok "${line}: offered: $first" } else { bad "${line}: not offered: $first" }
  if ($t -match 'CI Mallory') { bad "${line}: a copy outside Program Files accepted" } else { ok "${line}: a copy outside Program Files — not offered" }
  if ($t -match 'not our program behind the pipe of messenger') { ok "${line}: the log says why" } else { bad "${line}: no line about the fake" }
  if ((Get-Content C:\donor\messenger.log -Raw) -match 'state|approve') { bad "${line}: the fake received a request" } else { ok "${line}: the fake received nothing" }
  if ($vice) {
    if ($t -match 'donor (cloudvpn|mail)') { bad 'vice: a CloudVPN account accepted' } else { ok 'vice: accounts of cabinet.zjd.uk (and older programs) — not offered' }
  } else {
    if ($t -match 'donor wave') { bad 'cloud: an account of vice.zjd.uk accepted' } else { ok 'cloud: an account of vice.zjd.uk — not offered' }
    if ($t -match 'donor mail / CI Old') { ok 'cloud: a program older than the field answers for CloudVPN' } else { bad 'cloud: the older program is missing' }
  }
  if ($t -match 'sign-in: not confirmed' -and $t -notmatch 'sign-in: done' -and (cab-count 'poll: signed in') -eq $done) { ok "${line}: refusal — no session" } else { bad "${line}: refusal" }
  $req = (Get-Content "C:\donor\$(if ($vice) { 'wave' } else { 'cloudvpn' }).log" -Raw)
  if ($req -match '"approve"' -and $req -notmatch '"pub"') { ok "${line}: the request carries only the code and the service (no key is asked for)" } else { bad "${line}: request: $req" }
  start-donors 'allow'
  $t = run-signin $dir 'through' 'profiles from the account' 60
  if ($t -match 'confirmed by another program' -and $t -match 'sign-in: done') { ok "${line}: signed in through the other program" } else { bad "${line}: not signed in through the other program" }
  if ($t -match '[0-9A-F]{4}-[0-9A-F]{4}\b.*\n' -and ($t -split "`n" | Select-String 'Tgx:.*[0-9A-F]{4}-[0-9A-F]{4}')) { bad "${line}: a code in the log" } else { ok "${line}: no codes in the log" }
  stop-donors
  Remove-Item "$tgx\account*.json", "$tgx\profiles*.json", "$tgx\bypass.json" -ErrorAction SilentlyContinue
  Remove-NetFirewallRule -DisplayName 'talk-block-signin' -ErrorAction SilentlyContinue
}

# ---------- Cloud
if (fetch 'setup-url.txt' 'setup-sha.txt' "$env:TEMP\CloudTalk-Setup.exe") {
  Start-Process "$env:TEMP\CloudTalk-Setup.exe" -ArgumentList '/S' -Wait
  $dir = 'C:\Program Files\Cloud Talk'
  foreach ($f in 'CloudTalk.exe','xray.exe','talk.json','brand.json','uninstall.exe') { if (Test-Path "$dir\$f") { ok "Cloud: $f installed" } else { bad "Cloud: no $f" } }
  if ((Get-Content "$dir\brand.json" -Raw) -match '"brand":"cloud"') { ok 'Cloud: brand.json cloud' } else { bad "Cloud: brand.json $(Get-Content "$dir\brand.json" -Raw)" }
  if (Test-Path "$env:ProgramData\Microsoft\Windows\Start Menu\Programs\Cloud Talk.lnk") { ok 'Cloud: Start menu shortcut' } else { note 'Cloud: no Start menu shortcut in ProgramData' }
  run-line $dir 'cloud' 'Cloud Talk'
  check-signin $dir 'cloud'
}
# ---------- in-app update: an older version of the client (talk.json 0.0.9) downloads this installer and restarts itself
if ($dir -and (Test-Path "$dir\CloudTalk.exe")) {
  New-Item -ItemType Directory -Force C:\updsrv | Out-Null
  $ver = (Get-Content "$dir\talk.json" -Raw | ConvertFrom-Json).version
  if (-not $ver) { $ver = '0.1.0' }
  $setup = "CloudTalk-Setup-$ver.exe"
  Copy-Item "$env:TEMP\CloudTalk-Setup.exe" "C:\updsrv\$setup"
  $sha = (Get-FileHash "C:\updsrv\$setup" -Algorithm SHA256).Hash.ToLower()
  @{ products = @{ 'talk-win' = @{ title = 'Cloud Talk'; version = $ver; notes = 'CI'; files = @(@{ label = 'Windows'; flavor = 'talk-win'; name = $setup; sha256 = $sha; server = "http://127.0.0.1:8080/$setup" }) } } } | ConvertTo-Json -Depth 8 | Out-File C:\updsrv\downloads.json -Encoding ascii
  $web = Start-Process python -ArgumentList '-m','http.server','8080','--bind','127.0.0.1' -WorkingDirectory C:\updsrv -PassThru -WindowStyle Hidden
  Start-Sleep 2
  $old = $talkcfg | ConvertFrom-Json
  $old.version = '0.0.9'
  $old | Add-Member -NotePropertyName downloads -NotePropertyValue 'http://127.0.0.1:8080/downloads.json' -Force
  ($old | ConvertTo-Json -Depth 20 -Compress) | Out-File "$dir\talk.json" -Encoding ascii
  Remove-Item "$env:APPDATA\Cloud Talk\log.txt" -ErrorAction SilentlyContinue
  $env:TGX_CI_AUTO_UPDATE = '1'
  $oldp = start-talk "$dir\CloudTalk.exe"
  $env:TGX_CI_AUTO_UPDATE = ''
  $deadline = (Get-Date).AddSeconds(240); $started = $false
  while ((Get-Date) -lt $deadline) {
    Start-Sleep 3
    $t = Get-Content "$env:APPDATA\Cloud Talk\log.txt" -Raw -ErrorAction SilentlyContinue
    if ($t -match 'update installer started') { $started = $true; break }
  }
  $lg = Get-Content "$env:APPDATA\Cloud Talk\log.txt" -Raw -ErrorAction SilentlyContinue
  if ($lg -match 'update check: mine 0\.0\.9, latest') { ok 'update: the old client sees the new version' } else { bad 'update: no update check in the log' }
  if ($started) { ok 'update: downloaded inside the client, sha256 checked, installer started' } else { bad 'update: installer was not started' }
  $deadline = (Get-Date).AddSeconds(90); $again = $false
  while ((Get-Date) -lt $deadline) {
    Start-Sleep 3
    $p2 = Get-Process CloudTalk -ErrorAction SilentlyContinue | Where-Object { $_.Id -ne $oldp.Id }
    if ($p2) { $again = $true; break }
  }
  $dv = (Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\CloudTalk' -ErrorAction SilentlyContinue).DisplayVersion
  if ($dv -eq $ver) { ok "update: installed version in the system: $dv" } else { bad "update: version in the system: $dv" }
  if ($again) { ok 'update: the program opened again by itself' } else { bad 'update: the program did not open again' }
  Stop-Process -Name CloudTalk -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $web.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep 3
}
$n = (Select-String -Path C:\xrsrv\access.log -Pattern '(149\.154\.|91\.108\.)[0-9.]+:(443|80|5222)' -ErrorAction SilentlyContinue | Measure-Object).Count
if ($n -gt 0) { ok "test server: $n connections to Telegram data centers went through it" } else { bad 'test server: no Telegram connections in its log' }

# ---------- Vice over Cloud
if ((Test-Path 'vice-url.txt') -and (fetch 'vice-url.txt' 'vice-sha.txt' "$env:TEMP\ViceTalk-Setup.exe")) {
  Start-Process "$env:TEMP\ViceTalk-Setup.exe" -ArgumentList '/S' -Wait
  $vdir = 'C:\Program Files\Vice Talk'
  if (Test-Path "$vdir\CloudTalk.exe") { ok 'Vice: installed into its own folder' } else { bad 'Vice: not in C:\Program Files\Vice Talk' }
  if (-not (Test-Path 'C:\Program Files\Cloud Talk\CloudTalk.exe')) { ok 'Vice: Cloud line removed' } else { bad 'Vice: Cloud line still installed' }
  if ((Get-Content "$vdir\brand.json" -Raw) -match '"brand":"vice"') { ok 'Vice: brand.json vice' } else { bad 'Vice: brand.json' }
  run-line $vdir 'vice' 'Vice Talk'
  # the Cloud session of the same data folder is not used by the Vice line
  '{"refresh":"r-cloud","name":"CI Anna"}' | Out-File "$tgx\account.json" -Encoding ascii
  $t = run-signin $vdir 'list' 'program\(s\) can confirm' 40 $true
  if ((Test-Path "$tgx\account.json") -and $t -match 'can confirm' -and $t -notmatch 'profiles') { ok 'vice: the CloudVPN session is kept apart and not used' } else { bad 'vice: the CloudVPN session' }
  check-signin $vdir 'vice'
  Start-Process "$vdir\uninstall.exe" -ArgumentList '/S' -Wait
  Start-Sleep 3
  if (-not (Test-Path "$vdir\CloudTalk.exe")) { ok 'uninstall /S removed the program' } else { bad 'uninstall left the program' }
  if (Test-Path "$env:APPDATA\Cloud Talk") { ok 'uninstall kept the user data folder' } else { note 'no user data folder after uninstall' }
}
Copy-Item C:\xrsrv\access.log "$R\server-access.log" -ErrorAction SilentlyContinue
"FAILS=$script:fails" | Tee-Object -FilePath $SUM -Append
exit ([int]($script:fails -gt 0))
