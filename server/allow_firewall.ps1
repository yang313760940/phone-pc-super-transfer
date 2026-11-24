# 需以管理员身份运行
$rule = "PhoneVoice-MVP-8443"
if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
  New-NetFirewallRule -DisplayName $rule -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8443 -Profile Private,Public -Description "手机语音转写 MVP：局域网 HTTPS 入站" | Out-Null
}
Get-NetFirewallRule -DisplayName $rule | Select-Object DisplayName,Enabled,Direction,Action,Profile | Format-List
