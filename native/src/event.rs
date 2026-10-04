//! JNI 的事件协议；候选选择必须携带所显示帧的版本。
use serde::Deserialize;

#[derive(Default, Deserialize)]
#[serde(default)]
pub struct Event {
    pub kind: String,
    pub text: String,
    pub index: usize,
    pub revision: u64,
    pub private: bool,
    pub english: bool,
    pub before: String,
    pub after: String,
    pub page: usize,
}
