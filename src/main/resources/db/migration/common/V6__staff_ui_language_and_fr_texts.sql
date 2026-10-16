-- Staff UI language (NULL = not chosen yet, shown in French) and the French twin of each timeline summary.
ALTER TABLE staff_members ADD COLUMN ui_language VARCHAR(2) NULL;
ALTER TABLE staff_members ADD CONSTRAINT staff_members_ui_language_check CHECK (ui_language IS NULL OR ui_language IN ('FR','EN'));
ALTER TABLE patient_activity ADD COLUMN summary_fr VARCHAR(300) NULL;
