-- V14: Fix admin password hash to valid BCrypt
-- Correct hash for 'admin123'

UPDATE users 
SET password_hash = '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.TVuHOnu' 
WHERE username = 'admin';

-- Also insert demo user
INSERT INTO users (username, password_hash, role)
SELECT 'demo', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.TVuHOnu', 'USER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'demo');
