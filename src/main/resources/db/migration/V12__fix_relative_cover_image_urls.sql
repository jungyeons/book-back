-- Fix relative cover image URLs (e.g. /kyobo_images/S000xxx.jpg)
-- Map them to the Kyobo CDN full URL so the image-proxy SSRF endpoint can fetch them.
UPDATE books
SET cover_image_url = CONCAT(
    'https://contents.kyobobook.co.kr/sih/fit-in/400x0/pdt/',
    SUBSTRING_INDEX(cover_image_url, '/', -1)
)
WHERE cover_image_url LIKE '/kyobo_images/%'
   OR (cover_image_url NOT LIKE 'http%' AND cover_image_url LIKE '%.jpg');
