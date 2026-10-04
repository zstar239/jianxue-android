# 简学输入法署名与许可

简学输入法 Android 壳及 JNI 适配层：Copyright © 2026 简学输入法贡献者，GPL-3.0-or-later。完整许可见 `LICENSE`。

本项目复用 [Qingjian / 青简](https://github.com/qingjian-team/qingjian) 的引擎和数据，固定版本为 `c08ae57cb88b6a4a46f4a5e9c1d6d11c5e69222e`。原版权和许可位于 `vendor/qingjian/`。2026-10-03 的 Android 适配改动包含 HTTPS 使用 Rustls、Android 使用 Mozilla 根证书及禁用跨站重定向，补丁见 `patches/0001-android-tls.patch`。

这是独立移植，应用使用自己的名称与图标，没有青简官方授权或背书。上游的名称和 logo 不包含在 GPL 的授权内。

数据保留各自的许可与署名，不以应用 GPL 许可覆盖所有来源。完整来源文档随 APK 存放在 `assets/licenses/data/`，源码包保留原数据目录的 README 与许可证：

- 规范汉字：iDvel 的《通用规范汉字表》转录，shengdoushi 的分级字表交叉核对。
- 常用词：liuxilu 校对的《现代汉语常用词表》。领域词：[THUOCL](https://github.com/thunlp/THUOCL)，MIT 许可，版权声明位于 `assets/lexicon/00_meta/THUOCL_LICENSE.txt`。
- 英文补全：ESDB、CSpell、typos 等；各来源许可见 `vendor/qingjian/assets/lexicon/05_english/README.md`。
- 译词：青简的英、日、西班牙语和英译中表，部分由机器生成。数据的来源、生成说明及许可见 `vendor/qingjian/assets/` 内相应文档。
- 英语等级：**The CEFR-J Wordlist Version 1.5, Yukio Tono (Tokyo University of Foreign Studies)**，[CEFR-J](http://www.cefr-j.org/download.html)，研究与商业用途免费，须署名；**Octanove Vocabulary Profile C1/C2 1.0**，[Open Language Profiles](https://github.com/openlanguageprofiles/olp-en-cefrj)，CC BY-SA 4.0。
- 日语等级：**Jonathan Waller / Tanos** 的 [JLPT 词表](http://www.tanos.co.uk/jlpt/)，CC BY，须署名并链接；[elzup 的 CSV 整理](https://github.com/elzup/jlpt-word-list)，MIT。
- 整句模型：青简 `data-v3` 中的含章·通变 small；保留上游模型源码、说明与原许可。产品包 SHA-256：`42ad08fb2fe9f497c0ab191c120f05386f6adcc9d713c3283200aaed690e62f3`。
- Rust 第三方依赖的准确版本见 `native/Cargo.lock`。源码交付包包含这些依赖的源码和原许可；Android 构建与测试依赖的版本见 Gradle 配置。

分发本项目的 APK 时，请同时提供对应版本源码与构建说明，并保留相关数据的许可和署名。不得将独立移植宣传为青简官方 Android 版本。
