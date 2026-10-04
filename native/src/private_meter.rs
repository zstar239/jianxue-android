//! 安卓私密模式连聚合统计也不写，不能仅依赖上游 Engine 的学习开关。
use qingjian_core::engine::{Usage, UsageMeter, UsageSummary};
use qingjian_learning::UsageStats;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

pub struct PrivateMeter {
    pub stats: UsageStats,
    pub private: Arc<AtomicBool>,
}

impl UsageMeter for PrivateMeter {
    fn record(&mut self, usage: Usage) {
        if !self.private.load(Ordering::Relaxed) {
            self.stats.record(usage);
        }
    }
    fn flush(&mut self) {
        self.stats.flush();
    }
    fn summary(&self) -> UsageSummary {
        self.stats.summary()
    }
}
