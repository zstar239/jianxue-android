//! 可序列化的显示帧；安卓界面无需访问词库或排序算法。
use serde::Serialize;

#[derive(Default, Serialize)]
pub struct Frame {
    pub revision: u64,
    pub raw: String,
    pub preedit: String,
    pub candidates: Vec<Item>,
    pub all_candidates: Vec<Item>,
    pub spellings: Vec<String>,
    pub digits: String,
    pub locked: String,
    pub page: usize,
    pub pages: usize,
    pub committed: String,
    pub delete: bool,
    pub completion: Option<String>,
    pub private: bool,
    pub error: Option<String>,
    pub neural: bool,
    pub warning: Option<String>,
    pub translating: bool,
}

#[derive(Serialize)]
pub struct Item {
    pub index: usize,
    pub text: String,
    pub gloss: Option<String>,
    pub reading: Option<String>,
    pub fresh: bool,
    pub source: String,
    pub code: Option<String>,
}
