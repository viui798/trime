# 语音插件

从 1.2.13 起，APK 里不再内置语音插件。需要哪家就用哪家：

- 手机里已经装了**说点啥**、**小狐狸（Foxy）**、Google 语音、小米语音这类提供语音识别服务的应用，
  在「设置 → 便捷功能 → 语音输入 → 首选语音输入法」里直接选，长按空格说话即可，不切换输入法。
- 想用下面这些云服务，下载对应的 `.js` 文件，在同一个页面点「导入 JS 插件」选中它。

| 文件 | 服务 | 需要的凭据 |
| --- | --- | --- |
| `qwen-asr.js` | 通义千问（阿里百炼） | DashScope API Key |
| `doubao-asr.js` | 豆包（火山引擎） | App ID + Access Token |
| `tencent-asr.js` | 腾讯云语音识别 | SecretId + SecretKey |
| `tencent-translate.js` | 腾讯云语音翻译 | SecretId + SecretKey |

导入后在「选择语音引擎」里选中它，再点进插件设置填密钥。插件联网前会先征求你的同意，
只会访问它声明过的域名；密钥用 Android Keystore 加密保存，不交给脚本。

插件的写法参考 [Foxy 的插件格式](https://github.com/fxliang/foxy)；这里的四个是芝版自己写的，
源码就在本目录，改完重新导入即可。
