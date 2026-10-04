//! 安卓配置在 Rust 侧的镜像，输入算法仍由上游 Engine 执行。
use serde::Deserialize;

#[derive(Clone, Deserialize)]
#[serde(default)]
pub struct Config {
    pub language: String,
    pub scheme: String,
    pub traditional: bool,
    pub learning: bool,
    pub neural: bool,
    pub fuzzy: bool,
    pub cloud: bool,
    pub endpoint: String,
    pub model: String,
    pub api_key: String,
    pub domains: Vec<String>,
    pub full_width: bool,
    pub auxiliary: bool,
    pub phrases: Vec<qingjian_core::CustomPhrase>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            language: "en".into(),
            scheme: "pinyin".into(),
            traditional: false,
            learning: true,
            neural: true,
            fuzzy: false,
            cloud: false,
            endpoint: String::new(),
            model: String::new(),
            api_key: String::new(),
            domains: Vec::new(),
            full_width: true,
            auxiliary: false,
            phrases: Vec::new(),
        }
    }
}
