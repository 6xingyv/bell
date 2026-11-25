use super::{PlayMode, QueueItem};

pub struct Queue {
    pub items: Vec<QueueItem>, // File paths or fds
    pub current_index: Option<usize>,
    pub shuffle_order: Vec<usize>,
}

impl Queue {
    pub fn new() -> Self {
        Self {
            items: Vec::new(),
            current_index: None,
            shuffle_order: Vec::new(),
        }
    }

    pub fn add(&mut self, item: QueueItem) {
        self.items.push(item);
    }

    pub fn add_path(&mut self, path: String) {
        self.items.push(QueueItem::Path(path));
    }

    pub fn add_fd(&mut self, fd: i32) {
        self.items.push(QueueItem::Fd(fd));
    }
    
    pub fn clear(&mut self) {
        self.items.clear();
        self.current_index = None;
        self.shuffle_order.clear();
    }

    pub fn set_current_index(&mut self, index: usize) {
        if index < self.items.len() {
            self.current_index = Some(index);
        }
    }

    pub fn next(&mut self, mode: PlayMode) -> Option<QueueItem> {
        if self.items.is_empty() {
            return None;
        }

        if let Some(idx) = self.current_index {
            match mode {
                PlayMode::SingleLoop => {
                    // Stay on the same index
                    return Some(self.items[idx].clone());
                }
                PlayMode::Shuffle => {
                    if self.shuffle_order.is_empty() || self.shuffle_order.len() != self.items.len() {
                        self.regenerate_shuffle_order(idx);
                    }
                    // Find current in shuffle order
                    if let Some(pos) = self.shuffle_order.iter().position(|&i| i == idx) {
                        let next_pos = pos + 1;
                        if next_pos < self.shuffle_order.len() {
                            self.current_index = Some(self.shuffle_order[next_pos]);
                            return Some(self.items[self.shuffle_order[next_pos]].clone());
                        } else {
                            // Reached end of shuffle list, loop back or stop
                            // Let's loop back for shuffle
                            self.regenerate_shuffle_order(usize::MAX); // Start fresh
                            self.current_index = Some(self.shuffle_order[0]);
                            return Some(self.items[self.shuffle_order[0]].clone());
                        }
                    }
                }
                PlayMode::ListLoop => {
                    let next_idx = (idx + 1) % self.items.len();
                    self.current_index = Some(next_idx);
                    return Some(self.items[next_idx].clone());
                }
                PlayMode::None => {
                    let next_idx = idx + 1;
                    if next_idx < self.items.len() {
                        self.current_index = Some(next_idx);
                        return Some(self.items[next_idx].clone());
                    } else {
                        return None; // End of list
                    }
                }
            }
        }

        // Initial play
        let idx = if mode == PlayMode::Shuffle {
            self.regenerate_shuffle_order(usize::MAX);
            self.shuffle_order[0]
        } else {
            0
        };
        self.current_index = Some(idx);
        Some(self.items[idx].clone())
    }

    pub fn previous(&mut self, mode: PlayMode) -> Option<QueueItem> {
        if self.items.is_empty() {
            return None;
        }

        if let Some(idx) = self.current_index {
            match mode {
                PlayMode::SingleLoop => {
                    return Some(self.items[idx].clone());
                }
                PlayMode::Shuffle => {
                    if self.shuffle_order.is_empty() || self.shuffle_order.len() != self.items.len() {
                        self.regenerate_shuffle_order(idx);
                    }
                    if let Some(pos) = self.shuffle_order.iter().position(|&i| i == idx) {
                        if pos > 0 {
                            let prev_pos = pos - 1;
                            self.current_index = Some(self.shuffle_order[prev_pos]);
                            return Some(self.items[self.shuffle_order[prev_pos]].clone());
                        } else {
                            let prev_pos = self.shuffle_order.len() - 1;
                            self.current_index = Some(self.shuffle_order[prev_pos]);
                            return Some(self.items[self.shuffle_order[prev_pos]].clone());
                        }
                    }
                }
                PlayMode::ListLoop => {
                    let prev_idx = if idx == 0 { self.items.len() - 1 } else { idx - 1 };
                    self.current_index = Some(prev_idx);
                    return Some(self.items[prev_idx].clone());
                }
                PlayMode::None => {
                    let prev_idx = if idx > 0 { idx - 1 } else { 0 };
                    self.current_index = Some(prev_idx);
                    return Some(self.items[prev_idx].clone());
                }
            }
        }

        let idx = 0;
        self.current_index = Some(idx);
        Some(self.items[idx].clone())
    }

    /// Peek at the next track without advancing the current index.
    pub fn peek_next(&self, mode: PlayMode) -> Option<QueueItem> {
        if self.items.is_empty() {
            return None;
        }

        if let Some(idx) = self.current_index {
            match mode {
                PlayMode::SingleLoop => {
                    return Some(self.items[idx].clone());
                }
                PlayMode::Shuffle => {
                    if self.shuffle_order.is_empty() || self.shuffle_order.len() != self.items.len() {
                        // Need to regenerate, but we can't mutate self in a non-mut method.
                        // Fall back to returning None or handle differently.
                        // For simplicity, we'll return None here and let next() handle regeneration.
                        return None;
                    }
                    if let Some(pos) = self.shuffle_order.iter().position(|&i| i == idx) {
                        let next_pos = pos + 1;
                        if next_pos < self.shuffle_order.len() {
                            return Some(self.items[self.shuffle_order[next_pos]].clone());
                        } else {
                            // Loop back to start of shuffle order
                            return Some(self.items[self.shuffle_order[0]].clone());
                        }
                    }
                }
                PlayMode::ListLoop => {
                    let next_idx = (idx + 1) % self.items.len();
                    return Some(self.items[next_idx].clone());
                }
                PlayMode::None => {
                    let next_idx = idx + 1;
                    if next_idx < self.items.len() {
                        return Some(self.items[next_idx].clone());
                    } else {
                        return None;
                    }
                }
            }
        }

        // If no current index, peek at first item
        if !self.items.is_empty() {
            return Some(self.items[0].clone());
        }
        None
    }

    fn regenerate_shuffle_order(&mut self, current_idx: usize) {
        use rand::seq::SliceRandom;
        let mut rng = rand::rng();
        
        self.shuffle_order.clear();
        for i in 0..self.items.len() {
            if i != current_idx {
                self.shuffle_order.push(i);
            }
        }
        self.shuffle_order.shuffle(&mut rng);
        
        if current_idx != usize::MAX && current_idx < self.items.len() {
            self.shuffle_order.insert(0, current_idx);
        }
    }
}

