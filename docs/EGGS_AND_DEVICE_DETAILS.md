# 彩蛋、设备详情和登录位置

## 彩蛋二维码

扫码入口先识别 `livingunlock:egg:`，只进入彩蛋页；原有 `wslp://pair` 仍进入原配对校验。无效彩蛋不会回落到配对或明文显示，也不执行二维码里的代码或 URL。

三种模式：A7 = AES-256-GCM；C4 = ChaCha20-Poly1305；P9 = Argon2id（32 MiB / 3 次 / 并行度 1）派生密钥后使用 AES-256-GCM。随机 nonce 12 字节，口令模式随机 salt 16 字节，认证标签 16 字节。版本、算法编号、密钥编号、salt 和 nonce 全部纳入认证。正文上限 1200 个 UTF-8 字节；解密在后台线程执行。

普通模式共用独立的内置彩蛋密钥 K1，可被逆向，不适合私密资料。口令模式使用 P0 标识，不保存口令。彩蛋密钥与蓝牙配对、Windows 密码、设备签名密钥无关。

将正文保存为 UTF-8 文本，然后在项目目录运行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-24'
.\tools\New-EggQr.ps1 -Mode AES -TextFile .\message.txt -OutputFile .\egg-aes.svg
.\tools\New-EggQr.ps1 -Mode ChaCha20 -TextFile .\message.txt -OutputFile .\egg-chacha.svg
.\tools\New-EggQr.ps1 -Mode Password -TextFile .\message.txt -OutputFile .\egg-password.svg
```

口令模式会隐藏输入；不在命令行参数中传口令。SVG 为黑白二维码，可在浏览器打开供手机扫码。工具拒绝覆盖已有输出。密集二维码请放大显示。

## 设备详情

点按已配对设备卡片打开浮窗。备注最多 40 个字符，保存后显示“备注*”，清空恢复原设备名。原名、系统、CPU、GPU、物理内存总量、蓝牙 MAC、连接方式、估算启动时间和采集时间在详情里显示。

设备信息是最近一次蓝牙同步的快照，不是实时遥测。新配对完成后、以及后续解锁连接时更新。旧设备在首次连接新版 Windows 锁屏组件前显示“等待电脑同步”。这些字段不参与身份认证。

扩展使用已有 Pong 帧承载 LUDI + nonce + AES-GCM 密文 + tag，密钥由配对密钥以 HMAC-SHA256 和独立用途字符串派生。密文包含 pcId 与采集时间；接收端拒绝不匹配、篡改或倒退的快照。旧客户端可忽略 Pong。配对附加信息超时不会改变配对成功结果。

## 登录时采集位置

Windows 主页开关默认关闭。开启时请求 Windows 位置权限，并为当前用户写入 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\LivingUnlockLocation`，下次登录执行当前客户端的 `--capture-boot-location` 参数。程序只采集一次，最多等待 25 秒后退出，不持续跟踪。

位置来自 Windows 定位服务（坐标与精度），不额外调用第三方地理编码服务。本地注册表只保留最近一次，绑定当前启动周期。启用开关不会将现在的地点伪装成过去的开机位置。关闭时删除启动项及本地位置；手机旧快照在下次同步后更新。Windows 无权限、无法定位或未到下次登录时，显示未记录。该地点准确含义是“本次启动后的登录时采集位置”，不保证是机器通电瞬间所在位置。

位置只发送给持有配对密钥的手机，使用上述加密通道。Windows 原生 PIN 和密码入口不受影响。

## 解锁倒计时

Windows 使用挑战发出时确定的 30 秒截止时间，按绝对截止时间等待完整响应，部分数据到达不会续期。Android 根据挑战时间与 TTL 初始化剩余时间，之后使用单调时钟；UI 向上取整显示秒数，进度条依据挑战 TTL。双方系统时钟应保持同步，传输断开和用户取消可提前结束请求。
