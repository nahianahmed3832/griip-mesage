-- Batch B8b (Discord reference EVENT-CTRL-002): how a community event repeats ('weekly', 'biweekly', 'monthly' on the
-- same weekday, 'yearly'); NULL: once. A repeating event moves to its next occurrence when one ends. Additive.
ALTER TABLE meetings ADD COLUMN repeat TEXT;
