ALTER TABLE popups ADD COLUMN popup_type VARCHAR(20) NOT NULL DEFAULT 'update' AFTER device_type;
ALTER TABLE popups ADD COLUMN image_url  VARCHAR(500) NULL                        AFTER popup_type;
