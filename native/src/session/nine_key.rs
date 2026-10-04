//! 九键候选路由与 Engine 的组句消费对齐。
use super::Session;
use crate::t9::T9;
use qingjian_core::candidate::{Candidate, CandidateKind};
use qingjian_dictionary::Dictionary;

impl Session {
    pub(super) fn t9_active(&self) -> bool {
        self.t9.as_ref().is_some_and(|t9| !t9.digits.is_empty())
    }

    pub(super) fn clear_t9(&mut self) {
        if let Some(t9) = &mut self.t9 {
            t9.clear();
        }
        self.t9_sources.clear();
    }

    pub(super) fn take_t9_raw(&mut self) -> String {
        let digits = self
            .t9
            .as_ref()
            .map(|t9| t9.digits.clone())
            .unwrap_or_default();
        self.clear_t9();
        self.engine.clear();
        digits
    }

    pub(super) fn load_t9(&self) -> Result<T9, String> {
        let mut dictionaries =
            vec![Dictionary::from_path(self.data_dir.join("dict.qj")).map_err(|e| e.to_string())?];
        for name in &self.config.domains {
            if name.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') {
                dictionaries.push(
                    Dictionary::from_path(self.data_dir.join("dicts").join(format!("{name}.qj")))
                        .map_err(|e| e.to_string())?,
                );
            }
        }
        let imports = self.user_dir.join("dicts");
        if imports.is_dir() {
            for entry in std::fs::read_dir(imports).map_err(|e| e.to_string())? {
                let path = entry.map_err(|e| e.to_string())?.path();
                if path
                    .file_name()
                    .is_some_and(|s| !s.to_string_lossy().starts_with('.'))
                    && path.extension().is_some_and(|s| s == "qj" || s == "tsv")
                {
                    dictionaries.push(Dictionary::from_path(path).map_err(|e| e.to_string())?);
                }
            }
        }
        Ok(T9::new(&dictionaries))
    }

    fn set_spelling(&mut self, spelling: &str) {
        self.engine.clear();
        for character in spelling.chars() {
            self.engine.push(character);
        }
    }

    pub(super) fn requery_t9(&mut self) {
        self.revision += 1;
        self.candidates.clear();
        self.t9_sources.clear();
        let spellings = self.t9.as_ref().expect("active index").resolve();
        let mut lists = Vec::new();
        let mut best = String::new();
        for spelling in spellings {
            self.set_spelling(&spelling);
            if let Ok(query) = self.engine.query() {
                let candidates: Vec<_> = query
                    .candidates
                    .items
                    .into_iter()
                    .filter(|c| c.kind != CandidateKind::English)
                    .take(32)
                    .collect();
                if best.is_empty() && !candidates.is_empty() {
                    best = spelling.clone();
                }
                lists.push((spelling, candidates));
            }
        }
        // 交织不同拼音的候选，各拼音内部仍采用上游排序。
        for rank in 0..32 {
            for (spelling, candidates) in &lists {
                if let Some(candidate) = candidates.get(rank) {
                    let key = (candidate.text.clone(), candidate.syllables.clone());
                    if let std::collections::hash_map::Entry::Vacant(entry) =
                        self.t9_sources.entry(key)
                    {
                        entry.insert(spelling.clone());
                        self.candidates.push(candidate.clone());
                    }
                }
            }
            if self.candidates.len() >= 96 {
                break;
            }
        }
        self.set_spelling(&best);
        self.preedit = if best.is_empty() {
            self.t9.as_ref().expect("active index").digits.clone()
        } else {
            best.replace('\'', " ")
        };
        self.page = self.page.min(self.layout().pages().saturating_sub(1));
    }

    pub(super) fn prepare_t9_candidate(&mut self, candidate: &Candidate) {
        if let Some(spelling) = self
            .t9_sources
            .get(&(candidate.text.clone(), candidate.syllables.clone()))
            .cloned()
        {
            self.set_spelling(&spelling);
        }
    }

    pub(super) fn consume_t9(&mut self, before: usize) {
        if self.t9_active() {
            let after = self
                .engine
                .composition()
                .text()
                .bytes()
                .filter(u8::is_ascii_alphabetic)
                .count();
            if let Some(t9) = &mut self.t9 {
                t9.consume(before.saturating_sub(after));
            }
        }
    }
}
