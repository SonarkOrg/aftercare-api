ALTER TABLE alerts ADD COLUMN alert_type VARCHAR(32) DEFAULT 'CHECK_IN_ANSWER' NOT NULL;
ALTER TABLE alerts ADD CONSTRAINT alerts_alert_type_check CHECK (alert_type IN ('CHECK_IN_ANSWER', 'MISSED_CHECKIN'));
ALTER TABLE alerts ADD COLUMN missed_date DATE;
ALTER TABLE alerts ALTER COLUMN check_in_id DROP NOT NULL;
ALTER TABLE alerts ALTER COLUMN link_id DROP NOT NULL;
ALTER TABLE alerts ALTER COLUMN answer_value DROP NOT NULL;
ALTER TABLE alerts ADD CONSTRAINT alerts_shape_check CHECK (
    (alert_type = 'CHECK_IN_ANSWER' AND check_in_id IS NOT NULL AND link_id IS NOT NULL AND answer_value IS NOT NULL AND missed_date IS NULL)
 OR (alert_type = 'MISSED_CHECKIN' AND check_in_id IS NULL AND missed_date IS NOT NULL));
ALTER TABLE alerts ADD CONSTRAINT uk_alerts_follow_up_missed_date UNIQUE (follow_up_id, missed_date);

CREATE TABLE job_locks (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    locked_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    locked_by VARCHAR(100),
    last_started_at TIMESTAMP(6) WITH TIME ZONE,
    last_finished_at TIMESTAMP(6) WITH TIME ZONE
);
