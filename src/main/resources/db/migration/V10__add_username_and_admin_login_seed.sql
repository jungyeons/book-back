ALTER TABLE users
    ADD COLUMN username VARCHAR(50) NULL AFTER id;

UPDATE users
SET username = 'admin'
WHERE email = 'admin@bookvillage.mock';

UPDATE users
SET username = LOWER(SUBSTRING_INDEX(email, '@', 1))
WHERE (username IS NULL OR username = '')
  AND email IS NOT NULL
  AND email <> '';

UPDATE users
SET username = CONCAT('user_', id)
WHERE username IS NULL OR username = '';

UPDATE users u
JOIN (
    SELECT username
    FROM users
    GROUP BY username
    HAVING COUNT(*) > 1
) dup ON dup.username = u.username
SET u.username = CONCAT(u.username, '_', u.id);

UPDATE users
SET password = SHA1('admin1234')
WHERE username = 'admin' OR email = 'admin@bookvillage.mock';

ALTER TABLE users
    MODIFY COLUMN username VARCHAR(50) NOT NULL;

ALTER TABLE users
    ADD UNIQUE KEY uk_users_username (username);
