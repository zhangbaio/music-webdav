-- V14: Fix admin password hash to valid BCrypt
-- Ensures the 'admin' user has the correct hash for 'admin123' even if V13 was partially applied or skipped.

UPDATE users 
SET password_hash = '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.TVuHOnu' 
WHERE username = 'admin';
