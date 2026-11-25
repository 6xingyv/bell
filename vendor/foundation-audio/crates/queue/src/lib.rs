use rand::seq::SliceRandom;
use types::{PlayMode, QueueItem};

#[derive(Debug, Clone, Copy, Default)]
struct ReplayGainOverride {
    is_overridden: bool,
    gain_db: Option<f32>,
}

#[derive(Debug, Clone)]
pub struct QueueEntry {
    item: QueueItem,
    replay_gain: ReplayGainOverride,
}

impl QueueEntry {
    pub fn item(&self) -> &QueueItem {
        &self.item
    }

    pub fn into_item(self) -> QueueItem {
        self.item
    }

    pub fn has_replay_gain_override(&self) -> bool {
        self.replay_gain.is_overridden
    }

    pub fn replay_gain_db(&self) -> Option<f32> {
        self.replay_gain.gain_db
    }
}

/// Playback queue with deterministic peek/advance semantics.
///
/// In shuffle mode `peek_next` may prepare the next shuffle cycle, but it never
/// changes the selected item. A following `next` is therefore guaranteed to
/// return the item that was peeked.
#[derive(Debug)]
pub struct Queue {
    items: Vec<QueueItem>,
    replay_gains: Vec<ReplayGainOverride>,
    current_index: Option<usize>,
    shuffle_order: Vec<usize>,
    shuffle_cursor: usize,
}

impl Queue {
    pub fn new() -> Self {
        Self {
            items: Vec::new(),
            replay_gains: Vec::new(),
            current_index: None,
            shuffle_order: Vec::new(),
            shuffle_cursor: 0,
        }
    }

    pub fn add(&mut self, item: QueueItem) {
        self.items.push(item);
        self.replay_gains.push(ReplayGainOverride::default());
        self.invalidate_shuffle();
    }

    pub fn add_path(&mut self, path: String) {
        self.add(QueueItem::path(path));
    }

    pub fn add_fd(&mut self, fd: i32) {
        self.add(QueueItem::fd(fd));
    }

    pub fn set_replay_gain(&mut self, index: usize, gain_db: Option<f32>) -> bool {
        let Some(replay_gain) = self.replay_gains.get_mut(index) else {
            return false;
        };
        replay_gain.is_overridden = true;
        replay_gain.gain_db = gain_db
            .filter(|gain| gain.is_finite())
            .map(|gain| gain.clamp(-60.0, 24.0));
        true
    }

    /// Move one item while preserving the identity of the currently playing
    /// entry and its ReplayGain override.
    pub fn move_item(&mut self, from: usize, to: usize) -> bool {
        if from >= self.items.len() || to >= self.items.len() {
            return false;
        }
        if from == to {
            return true;
        }
        let item = self.items.remove(from);
        let replay_gain = self.replay_gains.remove(from);
        self.items.insert(to, item);
        self.replay_gains.insert(to, replay_gain);
        self.current_index = self.current_index.map(|current| {
            if current == from {
                to
            } else if from < current && current <= to {
                current - 1
            } else if to <= current && current < from {
                current + 1
            } else {
                current
            }
        });
        self.invalidate_shuffle();
        true
    }

    /// Removes every item and returns them so resource-owning callers can
    /// release raw descriptors at their API boundary.
    pub fn drain(&mut self) -> Vec<QueueItem> {
        self.current_index = None;
        self.invalidate_shuffle();
        self.replay_gains.clear();
        std::mem::take(&mut self.items)
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn current_index(&self) -> Option<usize> {
        self.current_index
    }

    pub fn current(&self) -> Option<&QueueItem> {
        self.current_index.and_then(|index| self.items.get(index))
    }

    pub fn current_entry(&self) -> Option<QueueEntry> {
        self.current_index.and_then(|index| self.entry(index))
    }

    pub fn get(&self, index: usize) -> Option<&QueueItem> {
        self.items.get(index)
    }

    pub fn get_entry(&self, index: usize) -> Option<QueueEntry> {
        self.entry(index)
    }

    pub fn set_current_index(&mut self, index: usize) -> bool {
        if index >= self.items.len() {
            return false;
        }
        self.current_index = Some(index);
        self.invalidate_shuffle();
        true
    }

    pub fn restore_current_index(&mut self, index: Option<usize>) {
        self.current_index = index.filter(|&value| value < self.items.len());
        self.invalidate_shuffle();
    }

    pub fn next(&mut self, mode: PlayMode) -> Option<QueueItem> {
        self.next_entry(mode).map(QueueEntry::into_item)
    }

    pub fn next_entry(&mut self, mode: PlayMode) -> Option<QueueEntry> {
        let target = self.next_index(mode)?;
        self.commit_index(target, mode);
        self.entry(target)
    }

    pub fn previous(&mut self, mode: PlayMode) -> Option<QueueItem> {
        self.previous_entry(mode).map(QueueEntry::into_item)
    }

    pub fn previous_entry(&mut self, mode: PlayMode) -> Option<QueueEntry> {
        if self.items.is_empty() {
            return None;
        }

        let target = match self.current_index {
            None => 0,
            Some(index) => match mode {
                PlayMode::SingleLoop => index,
                PlayMode::Shuffle => {
                    self.ensure_shuffle(index);
                    if self.shuffle_cursor == 0 {
                        *self.shuffle_order.last()?
                    } else {
                        self.shuffle_order[self.shuffle_cursor - 1]
                    }
                }
                PlayMode::ListLoop => {
                    if index == 0 {
                        self.items.len() - 1
                    } else {
                        index - 1
                    }
                }
                PlayMode::None => index.checked_sub(1)?,
            },
        };

        self.commit_index(target, mode);
        self.entry(target)
    }

    /// Returns the next track without changing the selected queue index.
    pub fn peek_next(&mut self, mode: PlayMode) -> Option<QueueItem> {
        self.peek_next_entry(mode).map(QueueEntry::into_item)
    }

    pub fn peek_next_entry(&mut self, mode: PlayMode) -> Option<QueueEntry> {
        let target = self.next_index(mode)?;
        self.entry(target)
    }

    fn entry(&self, index: usize) -> Option<QueueEntry> {
        Some(QueueEntry {
            item: self.items.get(index)?.clone(),
            replay_gain: *self.replay_gains.get(index)?,
        })
    }

    fn next_index(&mut self, mode: PlayMode) -> Option<usize> {
        if self.items.is_empty() {
            return None;
        }

        let Some(index) = self.current_index else {
            return Some(if mode == PlayMode::Shuffle {
                self.prepare_initial_shuffle();
                self.shuffle_order[0]
            } else {
                0
            });
        };

        match mode {
            PlayMode::SingleLoop => Some(index),
            PlayMode::Shuffle => {
                self.ensure_shuffle(index);
                if self.items.len() == 1 {
                    return Some(index);
                }
                if self.shuffle_cursor + 1 >= self.shuffle_order.len() {
                    self.prepare_next_shuffle_cycle(index);
                }
                self.shuffle_order.get(self.shuffle_cursor + 1).copied()
            }
            PlayMode::ListLoop => Some((index + 1) % self.items.len()),
            PlayMode::None => self.items.get(index + 1).map(|_| index + 1),
        }
    }

    fn commit_index(&mut self, index: usize, mode: PlayMode) {
        self.current_index = Some(index);
        if mode == PlayMode::Shuffle {
            if let Some(position) = self.shuffle_order.iter().position(|&value| value == index) {
                self.shuffle_cursor = position;
            }
        } else {
            self.invalidate_shuffle();
        }
    }

    fn ensure_shuffle(&mut self, current: usize) {
        let valid = self.shuffle_order.len() == self.items.len()
            && self.shuffle_order.get(self.shuffle_cursor) == Some(&current);
        if !valid {
            self.shuffle_order = (0..self.items.len()).filter(|&i| i != current).collect();
            self.shuffle_order.shuffle(&mut rand::rng());
            self.shuffle_order.insert(0, current);
            self.shuffle_cursor = 0;
        }
    }

    fn prepare_initial_shuffle(&mut self) {
        self.shuffle_order = (0..self.items.len()).collect();
        self.shuffle_order.shuffle(&mut rand::rng());
        self.shuffle_cursor = 0;
    }

    fn prepare_next_shuffle_cycle(&mut self, current: usize) {
        let mut remaining: Vec<_> = (0..self.items.len()).filter(|&i| i != current).collect();
        remaining.shuffle(&mut rand::rng());
        self.shuffle_order.clear();
        self.shuffle_order.push(current);
        self.shuffle_order.extend(remaining);
        self.shuffle_cursor = 0;
    }

    fn invalidate_shuffle(&mut self) {
        self.shuffle_order.clear();
        self.shuffle_cursor = 0;
    }
}

impl Default for Queue {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn queue(size: usize) -> Queue {
        let mut queue = Queue::new();
        for index in 0..size {
            queue.add_path(index.to_string());
        }
        queue
    }

    fn path(item: QueueItem) -> String {
        item.as_path().expect("expected a path").to_owned()
    }

    #[test]
    fn none_mode_stops_at_both_ends() {
        let mut queue = queue(2);
        assert!(queue.set_current_index(0));
        assert!(queue.previous(PlayMode::None).is_none());
        assert_eq!(queue.current_index(), Some(0));
        assert_eq!(path(queue.next(PlayMode::None).unwrap()), "1");
        assert!(queue.next(PlayMode::None).is_none());
        assert_eq!(queue.current_index(), Some(1));
    }

    #[test]
    fn list_loop_wraps() {
        let mut queue = queue(2);
        queue.set_current_index(1);
        assert_eq!(path(queue.next(PlayMode::ListLoop).unwrap()), "0");
        assert_eq!(path(queue.previous(PlayMode::ListLoop).unwrap()), "1");
    }

    #[test]
    fn single_loop_reopens_the_current_item_in_both_directions() {
        let mut queue = queue(3);
        queue.set_current_index(1);

        assert_eq!(path(queue.peek_next(PlayMode::SingleLoop).unwrap()), "1");
        assert_eq!(path(queue.next(PlayMode::SingleLoop).unwrap()), "1");
        assert_eq!(path(queue.previous(PlayMode::SingleLoop).unwrap()), "1");
        assert_eq!(queue.current_index(), Some(1));
    }

    #[test]
    fn shuffle_peek_matches_advance_across_many_cycles() {
        let mut queue = queue(5);
        queue.set_current_index(0);
        for _ in 0..30 {
            let peeked = path(queue.peek_next(PlayMode::Shuffle).unwrap());
            let advanced = path(queue.next(PlayMode::Shuffle).unwrap());
            assert_eq!(peeked, advanced);
        }
    }

    #[test]
    fn shuffle_does_not_repeat_at_cycle_boundary() {
        let mut queue = queue(4);
        queue.set_current_index(0);
        for _ in 0..20 {
            let before = queue.current_index().unwrap();
            queue.next(PlayMode::Shuffle).unwrap();
            assert_ne!(queue.current_index().unwrap(), before);
        }
    }

    #[test]
    fn invalid_selection_preserves_current_item() {
        let mut queue = queue(2);
        queue.set_current_index(1);
        assert!(!queue.set_current_index(9));
        assert_eq!(queue.current_index(), Some(1));
    }

    #[test]
    fn replay_gain_is_carried_with_peeked_and_advanced_items() {
        let mut queue = queue(2);
        assert!(queue.set_replay_gain(1, Some(-7.25)));
        assert!(!queue.set_replay_gain(9, Some(1.0)));
        queue.set_current_index(0);
        assert!(
            queue
                .peek_next_entry(PlayMode::None)
                .unwrap()
                .has_replay_gain_override()
        );
        assert_eq!(
            queue
                .peek_next_entry(PlayMode::None)
                .unwrap()
                .replay_gain_db(),
            Some(-7.25)
        );
        assert_eq!(
            queue.next_entry(PlayMode::None).unwrap().replay_gain_db(),
            Some(-7.25)
        );
    }

    #[test]
    fn explicit_missing_gain_does_not_mean_metadata_fallback() {
        let mut queue = queue(1);
        queue.set_current_index(0);
        assert!(!queue.current_entry().unwrap().has_replay_gain_override());
        assert!(queue.set_replay_gain(0, None));
        assert!(queue.current_entry().unwrap().has_replay_gain_override());
        assert_eq!(queue.current_entry().unwrap().replay_gain_db(), None);
    }

    #[test]
    fn moving_items_preserves_current_identity_and_gain() {
        let mut queue = queue(4);
        queue.set_current_index(1);
        queue.set_replay_gain(1, Some(-5.0));
        assert!(queue.move_item(1, 3));
        assert_eq!(queue.current_index(), Some(3));
        assert_eq!(path(queue.current().unwrap().clone()), "1");
        assert_eq!(queue.current_entry().unwrap().replay_gain_db(), Some(-5.0));
        assert!(queue.move_item(0, 2));
        assert_eq!(queue.current_index(), Some(3));
        assert!(!queue.move_item(9, 0));
    }
}
