//! 验证真实 Engine 的点选、剩余拼音、译词、隐私和持久化边界。
use crate::config::Config;
use crate::event::Event;
use crate::session::Session;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

fn session() -> Session {
    let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vendor/qingjian/assets/sample");
    let temporary = std::env::temp_dir().join(format!(
        "jianxue-test-{}-{}",
        std::process::id(),
        SEQUENCE.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::create_dir_all(&temporary).unwrap();
    for (source, destination) in [
        ("dict.tsv", "dict.qj"),
        ("english.tsv", "english.tsv"),
        ("glossary-en.tsv", "glossary-en.tsv"),
    ] {
        std::fs::copy(data.join(source), temporary.join(destination)).unwrap();
    }
    std::fs::write(temporary.join("glossary-zh.tsv"), "hello\t你好\n").unwrap();
    std::fs::write(temporary.join("emoji-zh.tsv"), "笑\t😄\n").unwrap();
    // 测试使用合法 .qj；扩展名决定加载器，不能把 TSV 伪装成二进制。
    let dictionary = qingjian_dictionary::Dictionary::from_path(data.join("dict.tsv")).unwrap();
    let metadata = qingjian_format::Metadata::default();
    dictionary
        .write_qj(&temporary.join("dict.qj"), &metadata)
        .unwrap();
    Session::open(
        &temporary,
        &temporary.join("user"),
        Config {
            neural: false,
            ..Config::default()
        },
    )
    .unwrap()
}

#[test]
fn real_pinyin_candidates_and_translation_commit() {
    let mut session = session();
    let mut frame = session.apply(Event {
        kind: "input".into(),
        text: "kaifa".into(),
        ..Event::default()
    });
    frame = session.apply(Event {
        kind: "annotate".into(),
        revision: frame.revision,
        ..Event::default()
    });
    let candidate = frame.candidates.iter().find(|c| c.text == "开发").unwrap();
    assert!(candidate.gloss.is_some());
    let committed = session.apply(Event {
        kind: "translation".into(),
        index: candidate.index,
        revision: frame.revision,
        ..Event::default()
    });
    assert!(!committed.committed.is_empty());
    assert!(committed.raw.is_empty());
}

#[test]
fn old_candidate_revision_cannot_commit_new_input() {
    let mut session = session();
    let old = session.apply(Event {
        kind: "input".into(),
        text: "ni".into(),
        ..Event::default()
    });
    session.apply(Event {
        kind: "input".into(),
        text: "hao".into(),
        ..Event::default()
    });
    let result = session.apply(Event {
        kind: "choose".into(),
        revision: old.revision,
        ..Event::default()
    });
    assert!(result.committed.is_empty());
    assert!(result.error.is_some());
    assert_eq!(result.raw, "nihao");
}

#[test]
fn selecting_prefix_retains_unconsumed_pinyin() {
    let mut session = session();
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "kaifazhe".into(),
        ..Event::default()
    });
    session.apply(Event {
        kind: "page".into(),
        page: 0,
        ..Event::default()
    });
    // 从真实候选帧找词：页数较少的样例词库足以覆盖此用例。
    let candidate = frame.candidates.iter().find(|c| c.text == "开发").unwrap();
    let result = session.apply(Event {
        kind: "choose".into(),
        index: candidate.index,
        revision: frame.revision,
        ..Event::default()
    });
    assert_eq!(result.committed, "开发");
    assert_eq!(result.raw, "zhe");
}

#[test]
fn private_commit_does_not_change_usage_or_learning_files() {
    let mut session = session();
    let before = session.stats();
    session.apply(Event {
        kind: "start".into(),
        private: true,
        ..Event::default()
    });
    let pending = session.apply(Event {
        kind: "input".into(),
        text: "nihao".into(),
        ..Event::default()
    });
    session.apply(Event {
        kind: "annotate".into(),
        revision: pending.revision,
        ..Event::default()
    });
    session.apply(Event {
        kind: "space".into(),
        ..Event::default()
    });
    session.apply(Event {
        kind: "finish".into(),
        ..Event::default()
    });
    assert_eq!(session.stats(), before);
    assert!(!session.user_dir.join("user.tsv").exists());
    assert!(!session.user_dir.join("usage.tsv").exists());
    assert!(!session.user_dir.join("user-vocab.tsv").exists());
}

#[test]
fn new_editor_discards_previous_composition() {
    let mut session = session();
    session.apply(Event {
        kind: "input".into(),
        text: "nihao".into(),
        ..Event::default()
    });
    let frame = session.apply(Event {
        kind: "start".into(),
        private: true,
        ..Event::default()
    });
    assert!(frame.raw.is_empty());
    assert!(frame.candidates.is_empty());
    assert!(frame.private);
}

#[test]
fn fixed_phrase_keeps_ninth_cell_and_commits_exact_multiline_text() {
    let mut session = session();
    session
        .configure(Config {
            neural: false,
            phrases: vec![qingjian_core::CustomPhrase {
                code: "ee".into(),
                text: "联系我\nmail@example.com".into(),
                position: 9,
                enabled: true,
            }],
            ..Config::default()
        })
        .unwrap();
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "ee".into(),
        ..Event::default()
    });
    assert_eq!(frame.pages, 3);
    let frame = session.apply(Event {
        kind: "page".into(),
        page: 2,
        ..Event::default()
    });
    assert_eq!(frame.candidates.last().unwrap().index, 8);
    let committed = session.apply(Event {
        kind: "choose".into(),
        index: 8,
        revision: frame.revision,
        ..Event::default()
    });
    assert_eq!(committed.committed, "联系我\nmail@example.com");
}

#[test]
fn calculator_keys_and_unicode_keys_stay_in_the_engine_buffer() {
    let mut session = session();
    for character in "v1+2".chars() {
        session.apply(Event {
            kind: "key".into(),
            text: character.to_string(),
            ..Event::default()
        });
    }
    assert!(
        session
            .frame()
            .candidates
            .iter()
            .any(|candidate| candidate.text == "3")
    );
    session.apply(Event {
        kind: "clear".into(),
        ..Event::default()
    });
    for character in "u4e00".chars() {
        session.apply(Event {
            kind: "key".into(),
            text: character.to_string(),
            ..Event::default()
        });
    }
    assert!(
        session
            .frame()
            .candidates
            .iter()
            .any(|candidate| candidate.text == "一")
    );
}

#[test]
fn usage_and_vocabulary_survive_engine_recreation() {
    let mut session = session();
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "kaifa".into(),
        ..Event::default()
    });
    session.apply(Event {
        kind: "annotate".into(),
        revision: frame.revision,
        ..Event::default()
    });
    session.apply(Event {
        kind: "space".into(),
        ..Event::default()
    });
    session.apply(Event {
        kind: "flush".into(),
        ..Event::default()
    });
    let stats = session.stats();
    let mut restored = Session::open(
        &session.data_dir,
        &session.user_dir,
        Config {
            neural: false,
            ..Config::default()
        },
    )
    .unwrap();
    assert_eq!(restored.stats(), stats);
    assert!(
        !restored.vocabulary().unwrap()["entries"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[test]
fn invalid_cloud_configuration_preserves_local_input() {
    let mut session = session();
    session
        .configure(Config {
            cloud: true,
            endpoint: "http://invalid.test".into(),
            neural: false,
            ..Config::default()
        })
        .unwrap();
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "kaifa".into(),
        ..Event::default()
    });
    assert!(frame.warning.is_some());
    assert!(
        frame
            .candidates
            .iter()
            .any(|candidate| candidate.text == "开发")
    );
    assert!(!session.engine.prediction_enabled());
}

#[test]
fn overflow_keeps_every_character_and_backspace_clears_last_key() {
    let mut session = session();
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "a".repeat(129),
        ..Event::default()
    });
    assert_eq!(frame.committed.len(), 128);
    assert_eq!(frame.raw, "a");
    let deleted = session.apply(Event {
        kind: "backspace".into(),
        ..Event::default()
    });
    assert!(deleted.raw.is_empty());
    assert!(!deleted.delete);
}

#[test]
fn disabling_glosses_also_disables_english_to_chinese_annotations() {
    let mut session = session();
    session
        .configure(Config {
            language: "off".into(),
            neural: false,
            ..Config::default()
        })
        .unwrap();
    session.apply(Event {
        kind: "start".into(),
        english: true,
        ..Event::default()
    });
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "hello".into(),
        ..Event::default()
    });
    assert!(!frame.candidates.is_empty());
    let frame = session.apply(Event {
        kind: "annotate".into(),
        revision: frame.revision,
        ..Event::default()
    });
    assert!(
        frame
            .candidates
            .iter()
            .all(|candidate| candidate.gloss.is_none())
    );
}

#[test]
fn rime_dictionary_import_converts_and_enters_actual_candidates() {
    let mut session = session();
    let directory = session.user_dir.join("dicts");
    std::fs::create_dir_all(&directory).unwrap();
    std::fs::write(directory.join(".pending.tsv"), "# Rime dictionary\n---\nname: jianxue-test\nversion: \"1\"\nsort: by_weight\n...\n简学\tjian xue\t12000\n").unwrap();
    let imported = session.import_dictionary().unwrap();
    assert_eq!(imported["entries"], 1);
    let frame = session.apply(Event {
        kind: "input".into(),
        text: "jianxue".into(),
        ..Event::default()
    });
    assert!(
        frame
            .candidates
            .iter()
            .any(|candidate| candidate.text == "简学")
    );
}

#[test]
fn binary_qj_dictionary_import_detects_content_in_staged_file() {
    let mut session = session();
    let directory = session.user_dir.join("dicts");
    std::fs::create_dir_all(&directory).unwrap();
    std::fs::copy(
        session.data_dir.join("dict.qj"),
        directory.join(".pending.tsv"),
    )
    .unwrap();
    let imported = session.import_dictionary().unwrap();
    assert!(imported["entries"].as_u64().unwrap() > 0);
    assert!(
        std::fs::read_dir(directory)
            .unwrap()
            .flatten()
            .any(
                |entry| entry.file_name().to_string_lossy().starts_with("import-")
                    && entry
                        .path()
                        .extension()
                        .is_some_and(|suffix| suffix == "qj")
            )
    );
}

#[test]
fn nine_key_converts_and_preserves_prefix_remainder() {
    let mut session = session();
    let frame = session.apply(Event {
        kind: "t9".into(),
        text: "52432943".into(),
        ..Event::default()
    });
    assert_eq!(frame.raw, "52432943");
    let candidate = frame
        .all_candidates
        .iter()
        .find(|c| c.text == "开发")
        .expect("九键应能选择词前缀");
    let result = session.apply(Event {
        kind: "choose".into(),
        index: candidate.index,
        revision: frame.revision,
        ..Event::default()
    });
    assert_eq!(result.committed, "开发");
    assert_eq!(result.raw, "943");
    assert!(result.all_candidates.iter().any(|c| c.text == "这"));
    let mut frame = session.apply(Event {
        kind: "clear".into(),
        ..Event::default()
    });
    assert!(frame.raw.is_empty());
    frame = session.apply(Event {
        kind: "t9".into(),
        text: "64426".into(),
        ..Event::default()
    });
    assert!(
        frame.all_candidates.iter().any(|c| c.text == "你好"),
        "{}",
        frame.preedit
    );
    let candidate = frame
        .all_candidates
        .iter()
        .find(|c| c.text == "你好")
        .unwrap();
    let result = session.apply(Event {
        kind: "choose".into(),
        index: candidate.index,
        revision: frame.revision,
        ..Event::default()
    });
    assert_eq!(result.committed, "你好");
    assert!(result.raw.is_empty());
}

#[test]
fn nine_key_disambiguates_rejects_stale_selection_and_deletes_last_digit() {
    let mut session = session();
    let frame = session.apply(Event {
        kind: "t9".into(),
        text: "64426".into(),
        ..Event::default()
    });
    assert!(frame.spellings.contains(&"ni".into()));
    let locked = session.apply(Event {
        kind: "spelling".into(),
        text: "ni".into(),
        revision: frame.revision,
        ..Event::default()
    });
    assert_eq!(locked.locked, "ni");
    assert!(locked.preedit.starts_with("ni"));
    let stale = session.apply(Event {
        kind: "choose".into(),
        revision: frame.revision,
        ..Event::default()
    });
    assert!(stale.error.is_some());
    assert_eq!(stale.raw, "64426");
    for _ in 0..5 {
        session.apply(Event {
            kind: "backspace".into(),
            ..Event::default()
        });
    }
    let empty = session.frame();
    assert!(empty.raw.is_empty());
    assert!(empty.digits.is_empty());
    assert!(empty.all_candidates.iter().all(|c| c.text.is_empty()));
}

#[test]
fn nine_key_private_commit_and_mode_switch_do_not_leak_digits() {
    let mut session = session();
    let before = session.stats();
    session.apply(Event {
        kind: "start".into(),
        private: true,
        ..Event::default()
    });
    session.apply(Event {
        kind: "t9".into(),
        text: "64426".into(),
        ..Event::default()
    });
    let result = session.apply(Event {
        kind: "space".into(),
        ..Event::default()
    });
    assert_eq!(result.committed, "你好");
    assert_eq!(session.stats(), before);
    session.apply(Event {
        kind: "t9".into(),
        text: "64".into(),
        ..Event::default()
    });
    let result = session.apply(Event {
        kind: "mode".into(),
        english: true,
        ..Event::default()
    });
    assert_eq!(result.committed, "64");
    assert!(result.raw.is_empty());
    let result = session.apply(Event {
        kind: "t9".into(),
        text: "64".into(),
        ..Event::default()
    });
    assert!(result.error.is_some());
    session.apply(Event {
        kind: "start".into(),
        ..Event::default()
    });
    assert!(session.frame().raw.is_empty());
}

#[test]
fn nine_key_long_ambiguous_input_is_bounded() {
    let data =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vendor/qingjian/assets/sample/dict.tsv");
    let dictionary = qingjian_dictionary::Dictionary::from_path(data).unwrap();
    let mut t9 = crate::t9::T9::new(&[dictionary]);
    t9.digits = "64426".repeat(25);
    let start = std::time::Instant::now();
    assert!(t9.resolve().len() <= 8);
    assert!(start.elapsed().as_secs() < 2);
}
