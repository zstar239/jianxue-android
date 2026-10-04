//! 一条专用线程拥有一个 Engine；候选缓存与帧版本共同保证点选的词不会变。
mod nine_key;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use qingjian_core::CandidateLayout;
use qingjian_core::candidate::{Candidate, CandidateKind, CandidateList, Language};
use qingjian_core::emoji::EmojiTable;
use qingjian_core::engine::{Engine, NoTranslator, SurroundingText};
use qingjian_core::fuzzy::FuzzyRules;
use qingjian_dictionary::{AuxCodeTable, CodeTable, Dictionary, WordList};
use qingjian_learning::{FrequencyLearner, UsageStats, VocabularyBook};
use qingjian_lm::BigramModel;
use qingjian_neural::{CharScorer, P2cScorer};
use qingjian_predict::{CloudGlossFiller, CloudPredictor, PredictConfig};
use qingjian_translate::{Glossary, LayeredTranslator, LevelTable, PersonalGlossary};
use serde_json::{Value, json};

use crate::config::Config;
use crate::event::Event;
use crate::frame::{Frame, Item};
use crate::private_meter::PrivateMeter;
use crate::t9::T9;
use std::collections::HashMap;

const PAGE_SIZE: usize = 3;
const MAX_INPUT: usize = 128;

pub struct Session {
    pub engine: Engine,
    candidates: Vec<Candidate>,
    revision: u64,
    page: usize,
    preedit: String,
    completion: Option<String>,
    cloud_words: Vec<Candidate>,
    pub data_dir: PathBuf,
    pub user_dir: PathBuf,
    config: Config,
    private: Arc<AtomicBool>,
    warning: Option<String>,
    translating: bool,
    t9: Option<T9>,
    t9_sources: HashMap<(String, Vec<String>), String>,
}

impl Session {
    pub fn open(data: &Path, user: &Path, config: Config) -> Result<Self, String> {
        std::fs::create_dir_all(user).map_err(|e| e.to_string())?;
        let dictionary = Dictionary::from_path(data.join("dict.qj")).map_err(|e| e.to_string())?;
        let learner = FrequencyLearner::from_path(user.join("user.tsv"))
            .map_err(|e| format!("用户词频无法读取：{e}"))?;
        let mut vocabulary = VocabularyBook::open(user.join("user-vocab.tsv"));
        for language in [Language::English, Language::Japanese] {
            if let Ok(levels) =
                LevelTable::from_path(data.join(format!("levels-{}.tsv", language.code())))
            {
                vocabulary = vocabulary.with_levels(language, levels);
            }
        }
        let private = Arc::new(AtomicBool::new(false));
        let mut engine = Engine::new(dictionary)
            .with_learner(Box::new(learner))
            .with_usage_meter(Box::new(PrivateMeter {
                stats: UsageStats::open(user.join("usage.tsv")),
                private: private.clone(),
            }))
            .with_vocabulary_tracker(Box::new(vocabulary));
        if data.join("lm.qj").exists() {
            engine = engine.with_language_model(Box::new(
                BigramModel::from_path(&data.join("lm.qj")).map_err(|e| e.to_string())?,
            ));
        }
        engine = engine.with_english(
            WordList::from_path(data.join("english.tsv")).map_err(|e| e.to_string())?,
        );
        engine = engine.with_emoji(
            EmojiTable::from_path(data.join("emoji-zh.tsv")).map_err(|e| e.to_string())?,
        );
        engine = engine.with_english_translator(Box::new(load_glossary(data, Language::Chinese)?));
        let mut session = Self {
            engine,
            candidates: Vec::new(),
            revision: 0,
            page: 0,
            preedit: String::new(),
            completion: None,
            cloud_words: Vec::new(),
            data_dir: data.to_owned(),
            user_dir: user.to_owned(),
            config: Config::default(),
            private,
            warning: None,
            translating: false,
            t9: None,
            t9_sources: HashMap::new(),
        };
        session.configure(config)?;
        Ok(session)
    }

    pub fn configure(&mut self, config: Config) -> Result<(), String> {
        qingjian_core::custom_phrase::validate_phrases(&config.phrases)?;
        self.warning = None;
        self.engine.discard_input();
        self.t9 = None;
        self.cloud_words.clear();
        self.completion = None;
        let translator: Box<dyn qingjian_core::engine::Translator> = if config.language == "off" {
            Box::new(NoTranslator)
        } else {
            let language = config
                .language
                .parse::<Language>()
                .map_err(|e| e.to_string())?;
            Box::new(LayeredTranslator::new(
                load_glossary(&self.data_dir, language)?,
                PersonalGlossary::open(
                    language,
                    self.user_dir
                        .join(format!("user-glossary-{}.tsv", language.code())),
                ),
            ))
        };
        self.engine.set_translator(translator);
        self.engine.set_learning(config.learning);
        self.engine.set_custom_phrases(config.phrases.clone())?;
        self.engine.set_full_width_punctuation(config.full_width);
        self.engine.set_aux_enabled(config.auxiliary);
        self.engine.set_aux_show(config.auxiliary);
        self.engine.set_aux_codes(if config.auxiliary {
            vec![Arc::new(
                AuxCodeTable::from_path(self.data_dir.join("codes/stroke.qj"))
                    .map_err(|e| e.to_string())?,
            )]
        } else {
            Vec::new()
        });
        self.engine.set_traditional_mode(config.traditional);
        self.engine.set_phonetic(config.scheme != "wubi");
        self.engine
            .set_code_table(if config.scheme.starts_with("wubi") {
                Some(
                    CodeTable::from_path(self.data_dir.join("wubi86.tsv"))
                        .map_err(|e| e.to_string())?,
                )
            } else {
                None
            });
        self.engine.set_zhuyin_mode(config.scheme == "zhuyin");
        self.engine.set_shuangpin(config.scheme.parse().ok());
        self.engine.set_fuzzy(FuzzyRules {
            z_zh: config.fuzzy,
            c_ch: config.fuzzy,
            s_sh: config.fuzzy,
            an_ang: config.fuzzy,
            en_eng: config.fuzzy,
            in_ing: config.fuzzy,
            ..FuzzyRules::default()
        });
        let mut extra = Vec::new();
        for name in &config.domains {
            // 配置不允许借词库名访问应用私有目录以外的文件。
            if !name.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') {
                continue;
            }
            extra.push(
                Dictionary::from_path(self.data_dir.join("dicts").join(format!("{name}.qj")))
                    .map_err(|e| e.to_string())?,
            );
        }
        let imports = self.user_dir.join("dicts");
        if imports.is_dir() {
            for entry in std::fs::read_dir(imports).map_err(|e| e.to_string())? {
                let path = entry.map_err(|e| e.to_string())?.path();
                if path
                    .file_name()
                    .is_some_and(|name| !name.to_string_lossy().starts_with('.'))
                    && path.extension().is_some_and(|e| e == "tsv" || e == "qj")
                {
                    extra.push(Dictionary::from_path(path).map_err(|e| e.to_string())?);
                }
            }
        }
        self.engine.set_extra_dictionaries(extra);
        self.engine.set_async_sentence_scorer(None);
        if config.neural && self.data_dir.join("model.qjm").exists() {
            match CharScorer::load(&self.data_dir.join("model.qjm"))
                .ok()
                .and_then(P2cScorer::new)
            {
                Some(scorer) => self
                    .engine
                    .set_async_sentence_scorer(Some(Box::new(scorer))),
                None => self.warning = Some("本地整句模型未加载，基础输入仍可使用".into()),
            }
        }
        self.engine
            .set_predictor(Box::new(qingjian_core::engine::NoPredictor));
        self.engine
            .set_gloss_filler(Box::new(qingjian_core::engine::NoGlossFiller));
        if config.cloud {
            if !config.endpoint.starts_with("https://")
                || config.model.trim().is_empty()
                || config.api_key.trim().is_empty()
            {
                self.warning = Some("云联想未启用，请检查 HTTPS 接口、模型与密钥".into());
            } else {
                let predict = PredictConfig {
                    enabled: true,
                    base_url: config.endpoint.clone(),
                    model: config.model.clone(),
                    api_key: Some(config.api_key.clone()),
                    api_key_env: String::new(),
                    ..PredictConfig::default()
                };
                match CloudPredictor::new(&predict) {
                    Ok(predictor) => {
                        self.engine.set_predictor(Box::new(predictor));
                        if let Ok(filler) = CloudGlossFiller::new(&predict) {
                            self.engine.set_gloss_filler(Box::new(filler));
                        }
                    }
                    Err(_) => self.warning = Some("云联想未启用，基础输入仍可使用".into()),
                }
            }
        }
        self.config = config;
        self.requery();
        Ok(())
    }

    fn requery(&mut self) {
        if self.t9.as_ref().is_some_and(|t9| !t9.digits.is_empty()) {
            self.requery_t9();
            return;
        }
        self.t9_sources.clear();
        self.revision += 1;
        self.candidates.clear();
        self.preedit.clear();
        if let Ok(query) = self.engine.query() {
            self.preedit = query.marked_text();
            self.candidates = query.candidates.items;
        }
        self.page = self.page.min(self.layout().pages().saturating_sub(1));
    }

    fn layout(&self) -> CandidateLayout {
        let mut layout = CandidateLayout::new(self.candidates.clone(), PAGE_SIZE, 1);
        layout.set_cloud(self.cloud_words.clone());
        layout
    }

    fn invalidate_async(&mut self) {
        self.engine.cancel_prediction();
        self.cloud_words.clear();
        self.completion = None;
        self.translating = false;
    }

    pub fn apply(&mut self, event: Event) -> Frame {
        let mut committed = String::new();
        let mut delete = false;
        let mut error = None;
        match event.kind.as_str() {
            "t9" => {
                if self.config.scheme != "pinyin" || self.engine.english_mode() {
                    error = Some("九键仅用于中文全拼，请切换输入方案".into());
                } else if event.text.bytes().all(|b| (b'2'..=b'9').contains(&b)) {
                    if self.t9.is_none() {
                        match self.load_t9() {
                            Ok(index) => self.t9 = Some(index),
                            Err(message) => {
                                let mut frame = self.frame();
                                frame.error = Some(message);
                                return frame;
                            }
                        }
                    }
                    if self.t9.as_ref().is_some_and(|t9| t9.digits.is_empty())
                        && !self.engine.composition().is_empty()
                    {
                        self.note_visible();
                        if let Some(candidate) = self.layout().candidate(0).cloned() {
                            committed = self.engine.commit(&candidate);
                        }
                        committed.push_str(&self.engine.take_raw());
                    }
                    let t9 = self.t9.as_mut().expect("index loaded");
                    for digit in event.text.chars() {
                        if t9.digits.len() >= MAX_INPUT {
                            committed.push_str(&t9.digits);
                            t9.clear();
                            self.warning = Some("九键组句达到 128 键，前段已按数字输入".into());
                        }
                        t9.digits.push(digit);
                    }
                    self.invalidate_async();
                    self.page = 0;
                    self.requery();
                }
            }
            "spelling" => {
                if event.revision != self.revision {
                    error = Some("拼音已更新，请重新选择".into());
                } else if let Some(t9) = &mut self.t9 {
                    if event.text.is_empty() {
                        t9.locked.clear();
                    } else if !t9.lock(&event.text) {
                        error = Some("拼音选择无效".into());
                    }
                    self.invalidate_async();
                    self.page = 0;
                    self.requery();
                }
            }
            "key" => {
                if let Some(character) = event.text.chars().next() {
                    if self.engine.aux_trigger(character) {
                        self.engine.enter_aux();
                        self.invalidate_async();
                        self.requery();
                        return self.frame();
                    }
                    if self.engine.in_aux() && self.engine.push_aux_code(character) {
                        self.invalidate_async();
                        self.requery();
                        return self.frame();
                    }
                    let compose = character.is_ascii_alphabetic()
                        || character == '\''
                        || self.engine.expression_mode()
                        || self.engine.question_mode()
                        || self.engine.raw_mode()
                        || (self.engine.is_zhuyin_mode() && "0123456789,.;/-".contains(character))
                        || (character == ';' && self.engine.takes_semicolon());
                    return self.apply(Event {
                        kind: if compose { "input" } else { "symbol" }.into(),
                        ..event
                    });
                }
            }
            "start" => {
                self.clear_t9();
                self.engine.discard_input();
                self.engine.set_private(event.private);
                self.private.store(event.private, Ordering::Relaxed);
                self.engine.set_english_mode(event.english);
                self.invalidate_async();
                self.page = 0;
                self.requery();
            }
            "input" => {
                if self.t9_active() {
                    committed = self.take_t9_raw();
                }
                self.invalidate_async();
                for character in event.text.chars() {
                    if self.engine.composition().text().len() >= MAX_INPUT {
                        committed.push_str(&self.engine.take_raw());
                        self.warning = Some("组句达到 128 键，前段已按原文输入".into());
                    }
                    self.engine.push(character);
                }
                self.page = 0;
                self.requery();
            }
            "choose" | "translation" => {
                if event.revision != self.revision {
                    error = Some("候选已更新，请重新选择".into());
                } else if let Some(candidate) = self.layout().candidate(event.index).cloned() {
                    self.note_visible();
                    self.prepare_t9_candidate(&candidate);
                    let before = self
                        .engine
                        .composition()
                        .text()
                        .bytes()
                        .filter(u8::is_ascii_alphabetic)
                        .count();
                    committed = if event.kind == "translation" {
                        self.engine
                            .commit_translation(&candidate, 0)
                            .unwrap_or_default()
                    } else {
                        self.engine.commit(&candidate)
                    };
                    self.consume_t9(before);
                    self.invalidate_async();
                    self.page = 0;
                    self.requery();
                }
            }
            "space" => {
                if self.engine.zhuyin_needs_tone() {
                    self.engine.push(' ');
                    self.invalidate_async();
                    self.requery();
                    return self.frame();
                }
                self.note_visible();
                committed = if let Some(candidate) = self.layout().candidate(0).cloned() {
                    self.prepare_t9_candidate(&candidate);
                    let before = self
                        .engine
                        .composition()
                        .text()
                        .bytes()
                        .filter(u8::is_ascii_alphabetic)
                        .count();
                    let text = self.engine.commit(&candidate);
                    self.consume_t9(before);
                    text
                } else if self.t9_active() {
                    self.take_t9_raw()
                } else if !self.engine.composition().is_empty() {
                    self.engine.take_raw()
                } else {
                    self.engine.note_passthrough(' ');
                    " ".into()
                };
                self.invalidate_async();
                self.page = 0;
                self.requery();
            }
            "raw" => {
                committed = if self.t9_active() {
                    self.take_t9_raw()
                } else {
                    self.engine.take_raw()
                };
                self.invalidate_async();
                self.requery();
            }
            "symbol" | "literal" => {
                self.note_visible();
                if let Some(candidate) = self.layout().candidate(0).cloned() {
                    self.prepare_t9_candidate(&candidate);
                    let before = self
                        .engine
                        .composition()
                        .text()
                        .bytes()
                        .filter(u8::is_ascii_alphabetic)
                        .count();
                    committed.push_str(&self.engine.commit(&candidate));
                    self.consume_t9(before);
                }
                // 标点要结束整段组句；部分候选余下的拼音保留成原文，避免静默丢字。
                if self.t9_active() {
                    committed.push_str(&self.take_t9_raw());
                } else if !self.engine.composition().is_empty() {
                    committed.push_str(&self.engine.take_raw());
                }
                for character in event.text.chars() {
                    if self.engine.english_mode() || event.kind == "literal" {
                        committed.push(character);
                        self.engine.note_passthrough(character);
                    } else if let Some(converted) = self.engine.punctuate(character) {
                        committed.push_str(converted);
                    } else {
                        committed.push(character);
                        self.engine.note_passthrough(character);
                    }
                }
                self.invalidate_async();
                self.requery();
            }
            "backspace" => {
                if self.t9_active() {
                    self.t9.as_mut().expect("active index").backspace();
                    if !self.t9_active() {
                        self.engine.clear();
                    }
                } else if !self.engine.backspace() {
                    self.engine.note_backspace();
                    delete = true;
                }
                self.invalidate_async();
                self.requery();
            }
            "left" | "right" => {
                if event.kind == "left" {
                    self.engine.move_cursor_left();
                } else {
                    self.engine.move_cursor_right();
                }
                self.invalidate_async();
                self.requery();
            }
            "page" => {
                self.page = event.page.min(self.layout().pages().saturating_sub(1));
                self.engine.note_page_turn();
            }
            "annotate" if event.revision == self.revision && self.config.language != "off" => {
                let local_len = self.candidates.len();
                let mut list = CandidateList {
                    items: std::mem::take(&mut self.candidates),
                };
                list.items.append(&mut self.cloud_words);
                self.engine.annotate(&mut list);
                self.cloud_words = list.items.split_off(local_len);
                self.candidates = list.items;
            }
            "idle" => {
                if !self.engine.is_private() && !self.t9_active() {
                    self.engine
                        .set_rescoring_context(Some(event.before.clone()));
                    self.engine.request_rescoring();
                    self.engine.request_prediction(
                        Some(SurroundingText {
                            before: event.before,
                            after: event.after,
                        }),
                        &self.candidates,
                    );
                }
            }
            "translate" => {
                if event.text.chars().count() > 2000 {
                    error = Some("选中文字超过 2000 字，请缩短选区".into());
                } else if !self.engine.composition().is_empty() {
                    error = Some("请先完成当前组句，再翻译选中文字".into());
                } else {
                    self.invalidate_async();
                    self.translating = self.engine.request_translation(&event.text).is_some();
                    if !self.translating {
                        error = Some("翻译需要启用云联想，并退出私密输入".into());
                    }
                    self.requery();
                }
            }
            "forget" if event.revision == self.revision => {
                if let Some(candidate) = self.layout().candidate(event.index).cloned() {
                    if self.engine.is_private() {
                        error = Some("私密输入不修改学习记录".into());
                    } else {
                        self.engine.forget(&candidate);
                        self.engine.flush_learning();
                        self.requery();
                    }
                }
            }
            "poll" => {
                let mut changed = self.engine.poll_rescoring();
                if self.engine.poll_glosses() > 0 {
                    changed = true;
                }
                if let Some(prediction) = self.engine.poll_prediction() {
                    self.cloud_words = prediction
                        .words
                        .into_iter()
                        .map(|w| w.into_candidate())
                        .collect();
                    self.completion = prediction.sentence;
                    if self.translating && self.completion.is_none() {
                        self.translating = false;
                        self.warning = Some("未获得译文，原文已保留".into());
                    }
                    changed = true;
                }
                if changed {
                    self.requery();
                }
            }
            "completion" if event.revision == self.revision => {
                if let Some(text) = self.completion.take() {
                    committed = if self.translating {
                        text
                    } else {
                        self.engine.accept_prediction(&text)
                    };
                }
                self.invalidate_async();
                self.requery();
            }
            "mode" => {
                if self.t9_active() {
                    committed = self.take_t9_raw();
                } else if !self.engine.composition().is_empty() {
                    committed = self.engine.take_raw();
                }
                self.engine.set_english_mode(event.english);
                self.invalidate_async();
                self.requery();
            }
            "finish" | "clear" => {
                self.clear_t9();
                self.engine.discard_input();
                self.invalidate_async();
                self.engine.flush_learning();
                self.requery();
            }
            "flush" => self.engine.flush_learning(),
            "frame" | "annotate" | "forget" => {}
            _ => error = Some("未知输入事件".into()),
        }
        let mut frame = self.frame();
        frame.committed = committed;
        frame.delete = delete;
        frame.error = error;
        frame
    }

    fn note_visible(&mut self) {
        let layout = self.layout();
        let page = layout.page(self.page);
        self.engine
            .note_displayed(page.iter().filter_map(|cell| cell.candidate()));
    }

    pub fn frame(&self) -> Frame {
        let layout = self.layout();
        let items = |page: usize| {
            layout
                .page(page)
                .iter()
                .enumerate()
                .map(|(slot, cell)| {
                    let index = page * PAGE_SIZE + slot;
                    let Some(candidate) = cell.candidate() else {
                        return Item {
                            index,
                            text: String::new(),
                            gloss: None,
                            reading: None,
                            fresh: false,
                            source: "empty".into(),
                            code: None,
                        };
                    };
                    let sense = candidate
                        .translation
                        .as_ref()
                        .and_then(|t| t.senses().first());
                    Item {
                        index,
                        text: qingjian_core::CustomPhrase::preview(&candidate.text, 24),
                        gloss: sense.map(|s| match s.part_of_speech {
                            Some(pos) => format!("{pos} {}", s.text),
                            None => s.text.clone(),
                        }),
                        reading: sense
                            .and_then(|s| s.reading.clone())
                            .or_else(|| candidate.reading.clone()),
                        fresh: sense.is_some_and(|s| s.fresh),
                        source: match candidate.kind {
                            CandidateKind::Cloud => "cloud",
                            CandidateKind::Sentence | CandidateKind::Generated => "sentence",
                            _ => "local",
                        }
                        .into(),
                        code: candidate.aux_code.clone(),
                    }
                })
                .collect::<Vec<Item>>()
        };
        Frame {
            revision: self.revision,
            raw: if self.t9_active() {
                self.t9.as_ref().expect("active index").digits.clone()
            } else {
                self.engine.composition().typed_text()
            },
            preedit: self.preedit.clone(),
            candidates: items(self.page),
            all_candidates: ((self.page / 32) * 32
                ..((self.page / 32 + 1) * 32).min(layout.pages()))
                .flat_map(items)
                .collect(),
            spellings: self.t9.as_ref().map(T9::options).unwrap_or_default(),
            digits: self
                .t9
                .as_ref()
                .map(|t9| t9.digits.clone())
                .unwrap_or_default(),
            locked: self
                .t9
                .as_ref()
                .map(|t9| t9.locked.clone())
                .unwrap_or_default(),
            page: self.page,
            pages: layout.pages(),
            completion: self.completion.clone(),
            private: self.engine.is_private(),
            neural: self.engine.has_sentence_scorer(),
            warning: self.warning.clone(),
            translating: self.translating,
            ..Frame::default()
        }
    }

    pub fn stats(&self) -> Value {
        let usage = self.engine.usage_summary();
        let vocabulary = self.engine.vocabulary_summary();
        let convert = |u: qingjian_core::engine::Usage| json!({ "hanzi": u.hanzi, "words": u.words, "english_words": u.english_words, "commits": u.commits });
        json!({ "today": convert(usage.today), "week": convert(usage.week), "total": convert(usage.total), "days": usage.days,
            "vocabulary": { "seen": vocabulary.seen, "familiar": vocabulary.familiar, "committed": vocabulary.committed, "used": vocabulary.used, "new_this_week": vocabulary.new_this_week } })
    }

    pub fn vocabulary(&mut self) -> Result<Value, String> {
        self.engine.flush_learning();
        let tsv = match std::fs::read_to_string(self.user_dir.join("user-vocab.tsv")) {
            Ok(text) => text,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
            Err(error) => return Err(error.to_string()),
        };
        let language = self.engine.learning_language().code();
        let mut rows: Vec<Value> = tsv.lines().filter(|line| !line.starts_with('#')).filter_map(|line| {
            let fields: Vec<&str> = line.split('\t').collect();
            if fields.len() != 7 || fields[0] != language { return None; }
            Some(json!({"word": fields[1], "seen": fields[2].parse::<u32>().ok()?, "committed": fields[3].parse::<u32>().ok()?,
                "used": fields[4].parse::<u32>().ok()?, "first": fields[5], "last": fields[6]}))
        }).collect();
        rows.sort_by(|a, b| {
            a["seen"]
                .as_u64()
                .cmp(&b["seen"].as_u64())
                .then_with(|| b["last"].as_str().cmp(&a["last"].as_str()))
        });
        Ok(json!({"entries": rows, "tsv": tsv}))
    }

    pub fn import_dictionary(&mut self) -> Result<Value, String> {
        let directory = self.user_dir.join("dicts");
        let pending = directory.join(".pending.tsv");
        let imported =
            qingjian_dictionary::import::import(&pending, &directory).map_err(|e| e.to_string())?;
        let destination = directory.join(format!(
            "import-{}.qj",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|e| e.to_string())?
                .as_nanos()
        ));
        std::fs::rename(imported.path, destination).map_err(|e| e.to_string())?;
        self.configure(self.config.clone())?;
        Ok(json!({"entries": imported.entries, "name": imported.name}))
    }

    pub fn reset(&mut self) -> Result<Value, String> {
        self.engine.discard_input();
        self.engine.flush_learning();
        for name in [
            "user.tsv",
            "user-words.tsv",
            "user-choices.tsv",
            "user-english.tsv",
            "user-ngram.tsv",
            "user-typos.tsv",
            "usage.tsv",
            "user-vocab.tsv",
            "user-glossary-en.tsv",
            "user-glossary-ja.tsv",
            "user-glossary-es.tsv",
        ] {
            let path = self.user_dir.join(name);
            if path.exists() {
                std::fs::remove_file(path).map_err(|e| e.to_string())?;
            }
        }
        let replacement = Self::open(&self.data_dir, &self.user_dir, self.config.clone())?;
        *self = replacement;
        Ok(json!({"reset": true}))
    }
}

fn load_glossary(data: &Path, language: Language) -> Result<Glossary, String> {
    let packed = data.join(format!("glossary-{}.qj", language.code()));
    let text = data.join(format!("glossary-{}.tsv", language.code()));
    Glossary::from_path(language, if packed.exists() { packed } else { text })
        .map_err(|e| e.to_string())
}
