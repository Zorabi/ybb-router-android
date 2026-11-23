# 固定签名与覆盖升级

同一应用只有使用匹配的签名证书，才能覆盖安装并保留数据。开发机自动生成的调试密钥可能不同，因此本项目打包 Debug、Release APK 或应用包时，都要求显式配置固定密钥；单元测试和静态检查不要求签名。

## 配置位置

默认读取 `~/.config/ybb-router/signing.properties`，也可通过 `YBB_SIGNING_PROPERTIES` 指定其他私有配置文件的绝对路径。字段见仓库中的 [配置示例](../signing.properties.example)：

| 字段 | 内容 |
| --- | --- |
| `storeFile` | 密钥文件的绝对路径 |
| `storePassword` | 密钥库密码 |
| `keyAlias` | 签名别名 |
| `keyPassword` | 私钥密码 |

真实配置和密钥必须保存在仓库之外，限制为本人可读写。仓库已忽略常见密钥文件和本地签名配置，不得将密码写入 Gradle 文件或提交记录。

## 已有安装

从原构建机器安全迁移密钥及密码，再配置本机路径；转换密钥容器格式或修改别名时必须保持原证书和私钥。Debug 和 Release 使用同一份配置。

迁移已有密钥时可以更换容器格式、别名和保护密码，但必须校验迁移前后证书一致。其他构建机器需要取得相同密钥，单靠拉取源码不能完成保留数据的升级。

密钥和密码应另行保存在可信的加密备份中。偏好备份不包含签名密钥，也不能替代签名密钥。若原签名丢失，应先在旧应用中导出常用记录，再处理重新安装；登录状态不包含在偏好备份中。

## 首次配置新签名

仅当没有需要兼容的旧安装时创建新密钥：

```sh
mkdir -p "$HOME/.config/ybb-router"
keytool -genkeypair \
  -keystore "$HOME/.config/ybb-router/signing.p12" \
  -storetype PKCS12 -alias ybbrouter \
  -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=dongx"
```

按终端提示设置密码，并参考 `signing.properties.example` 创建私有配置，填写实际路径、密码和别名。不要覆盖已有配置。

```sh
chmod 700 "$HOME/.config/ybb-router"
chmod 600 "$HOME/.config/ybb-router/signing.p12" \
  "$HOME/.config/ybb-router/signing.properties"
./gradlew :app:assembleDebug :app:assembleRelease
```

输出分别为 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/release/app-release.apk`。覆盖安装使用 `adb install -r <APK路径>`；遇到签名不匹配时先核对密钥，不应直接卸载应用。
