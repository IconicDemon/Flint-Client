//! Step timings for the work between a click and its result (Play to Java starting, say), for the debug log: one line
//! that says where the time went, so a slow launch can be explained from the log instead of guessed at.

use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Times consecutive steps. Shared by reference across the awaits of one operation; every call is cheap.
pub struct Steps {
    start: Instant,
    inner: Mutex<(Instant, Vec<(&'static str, Duration)>)>,
}

impl Steps {
    pub fn new() -> Self {
        let now = Instant::now();
        Steps { start: now, inner: Mutex::new((now, Vec::new())) }
    }

    /// Ends the step running since the last call (or since the start) and names it.
    pub fn step(&self, name: &'static str) {
        let now = Instant::now();
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let took = now - inner.0;
        inner.0 = now;
        inner.1.push((name, took));
    }

    /// "412 ms: check files 120, runtime 8, …" with steps under 1 ms left out (their time still counts in the total).
    pub fn summary(&self) -> String {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let steps: Vec<String> = inner.1.iter().filter(|(_, took)| took.as_micros() >= 1000).map(|(name, took)| format!("{name} {}", took.as_millis())).collect();
        let total = self.start.elapsed().as_millis();
        if steps.is_empty() {
            format!("{total} ms")
        } else {
            format!("{total} ms ({})", steps.join(", "))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn steps_are_named_in_order_and_short_ones_are_left_out_of_the_line() {
        let steps = Steps::new();
        std::thread::sleep(Duration::from_millis(3));
        steps.step("slow");
        steps.step("instant");
        let line = steps.summary();
        assert!(line.contains("(slow "), "{line}");
        assert!(!line.contains("instant"), "{line}");
        assert!(line.split(' ').next().unwrap().parse::<u64>().unwrap() >= 3, "{line}");
    }
}
