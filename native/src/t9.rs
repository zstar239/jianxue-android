//! 九键数字到拼音的词库索引与有界路径搜索；不枚举字母笛卡尔积。
use std::collections::HashMap;

use qingjian_core::parser::SYLLABLES;
use qingjian_dictionary::Dictionary;

type Routes = Vec<(String, f64)>;
const BEAM: usize = 8;

pub struct T9 {
    words: HashMap<String, Routes>,
    prefixes: HashMap<String, Routes>,
    cost: f64,
    pub digits: String,
    pub locked: String,
}

pub fn encode(text: &str) -> String {
    text.bytes()
        .filter_map(|b| match b {
            b'a'..=b'c' => Some('2'),
            b'd'..=b'f' => Some('3'),
            b'g'..=b'i' => Some('4'),
            b'j'..=b'l' => Some('5'),
            b'm'..=b'o' => Some('6'),
            b'p'..=b's' => Some('7'),
            b't'..=b'v' => Some('8'),
            b'w'..=b'z' => Some('9'),
            _ => None,
        })
        .collect()
}

fn prune(routes: &mut Routes) {
    routes.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    let mut seen = std::collections::HashSet::new();
    routes.retain(|route| seen.insert(route.0.clone()));
    routes.truncate(BEAM);
}

impl T9 {
    pub fn new(dictionaries: &[Dictionary]) -> Self {
        let cost = dictionaries
            .iter()
            .map(|d| d.total_frequency() as f64)
            .sum::<f64>()
            .max(1.0)
            .ln()
            .max(12.0);
        let mut words: HashMap<String, HashMap<String, u32>> = HashMap::new();
        let mut frequencies: HashMap<String, u64> = HashMap::new();
        for dictionary in dictionaries {
            for entry in dictionary.entries() {
                let spelling = entry.pinyin.replace(' ', "'");
                let code = encode(&spelling);
                if code.is_empty() || code.len() > 40 {
                    continue;
                }
                let frequency = words.entry(code).or_default().entry(spelling).or_default();
                *frequency = (*frequency).max(entry.frequency);
                for syllable in entry.syllables() {
                    *frequencies.entry(syllable.into()).or_default() += u64::from(entry.frequency);
                }
            }
        }
        let words = words
            .into_iter()
            .map(|(code, readings)| {
                let mut routes: Routes = readings
                    .into_iter()
                    .map(|(reading, frequency)| (reading, (f64::from(frequency) + 1.0).ln()))
                    .collect();
                prune(&mut routes);
                (code, routes)
            })
            .collect();
        let mut prefixes: HashMap<String, Routes> = HashMap::new();
        for syllable in SYLLABLES {
            let frequency = (*frequencies.get(*syllable).unwrap_or(&1) as f64 + 1.0).ln();
            for length in 1..=syllable.len() {
                let prefix = &syllable[..length];
                prefixes
                    .entry(encode(prefix))
                    .or_default()
                    .push((prefix.into(), frequency));
            }
        }
        for routes in prefixes.values_mut() {
            prune(routes);
        }
        Self {
            words,
            prefixes,
            cost,
            digits: String::new(),
            locked: String::new(),
        }
    }

    pub fn clear(&mut self) {
        self.digits.clear();
        self.locked.clear();
    }

    pub fn backspace(&mut self) {
        self.digits.pop();
        if encode(&self.locked).len() > self.digits.len() {
            self.locked.clear();
        }
    }

    pub fn consume(&mut self, letters: usize) {
        let count = letters.min(self.digits.len());
        self.digits.drain(..count);
        self.locked.clear();
    }

    pub fn options(&self) -> Vec<String> {
        let mut options = Vec::new();
        // 显示首音节，点选后锁定前缀；后续数字仍可连续组句。
        for length in (1..=self.digits.len().min(6)).rev() {
            if let Some(routes) = self.prefixes.get(&self.digits[..length]) {
                for (spelling, _) in routes {
                    if qingjian_core::parser::is_syllable(spelling) && !options.contains(spelling) {
                        options.push(spelling.clone());
                    }
                }
            }
        }
        options.truncate(24);
        options
    }

    pub fn lock(&mut self, spelling: &str) -> bool {
        if self.options().iter().any(|option| option == spelling) {
            self.locked = spelling.into();
            true
        } else {
            false
        }
    }

    pub fn resolve(&self) -> Vec<String> {
        let offset = encode(&self.locked).len();
        let input = &self.digits[offset.min(self.digits.len())..];
        if input.is_empty() {
            return if self.locked.is_empty() {
                Vec::new()
            } else {
                vec![self.locked.clone()]
            };
        }
        let mut paths: Vec<Routes> = vec![Vec::new(); input.len() + 1];
        paths[0].push((String::new(), 0.0));
        for start in 0..input.len() {
            prune(&mut paths[start]);
            let bases = paths[start].clone();
            for end in start + 1..=(start + 40).min(input.len()) {
                let code = &input[start..end];
                let mut tokens = self.words.get(code).cloned().unwrap_or_default();
                // 未打完的最后一个音节也参与搜索。
                if end == input.len()
                    && let Some(prefixes) = self.prefixes.get(code)
                {
                    tokens.extend(
                        prefixes
                            .iter()
                            .cloned()
                            .map(|(text, score)| (text, score - 8.0)),
                    );
                }
                for (base, score) in &bases {
                    for (token, weight) in &tokens {
                        let spelling = if base.is_empty() {
                            token.clone()
                        } else {
                            format!("{base}'{token}")
                        };
                        // 每个词段付固定代价，避免高频单字淹没完整词。
                        paths[end].push((spelling, score + weight - self.cost));
                    }
                }
                if paths[end].len() > BEAM * BEAM {
                    prune(&mut paths[end]);
                }
            }
        }
        prune(&mut paths[input.len()]);
        paths
            .pop()
            .unwrap_or_default()
            .into_iter()
            .map(|(spelling, _)| {
                if self.locked.is_empty() {
                    spelling
                } else {
                    format!("{}'{spelling}", self.locked)
                }
            })
            .collect()
    }
}
