-- Member timeouts (docs/product/MELDIQ_FEATURE_GAP_MATRIX.md MOD-06): until this time, the member can't post, share
-- files or join and talk in the community's voice channels. NULL or a past time: no timeout. Additive: older servers
-- ignore the column.
ALTER TABLE org_members ADD COLUMN timeout_until INTEGER;
