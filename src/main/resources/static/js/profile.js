// My profile (P31). Loaded only by the Languages and Photo pages (profile/languages.html, profile/photo.html), not by
// the layout, so My crew and every other page stay without it. Only enhancements: without it both pages work as they
// are (the server filters on Find, and receives the original photo).
//  - Languages, form[data-language-search]: the results (li[data-search], every language is in the page) filter as the
//    user types, folded as the server folds them (lower case, no accents; the code matches too). Find and Enter
//    filter in place instead of reloading. The count above the list follows (data-template, {count}) and "No language
//    matches your search." shows when nothing does.
//  - Photo, input[data-photo-input]: a chosen JPEG or PNG is made smaller in the browser before it is sent (a canvas,
//    at most 800 px on its longest side, JPEG), replaces the chosen file and is shown in [data-photo-preview]. Save
//    waits while that runs (data-resizing). Where the browser cannot (no canvas, no DataTransfer, an image it cannot
//    decode), the original is sent; the server re-encodes every photo anyway.
(function () {
    'use strict';

    // ---- Languages -------------------------------------------------------------------------------------------------

    function fold(text) {
        return (text || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().trim();
    }

    const search = document.querySelector('form[data-language-search]');
    if (search) {
        const input = search.querySelector('input[type=search]');
        const list = document.getElementById(input.getAttribute('aria-controls'));
        const rows = Array.from(list.querySelectorAll('li[data-search]'));
        const count = document.getElementById('languageResultCount');
        const none = list.parentElement.querySelector('.language-no-results');
        let timer = null;

        const filter = () => {
            const needle = fold(input.value);
            let shown = 0;
            rows.forEach(row => {
                const words = row.dataset.search;
                const at = words.lastIndexOf(' ');
                const match = !needle || words.slice(0, at).includes(needle) || words.slice(at + 1) === needle;
                row.hidden = !match;
                shown += match ? 1 : 0;
            });
            if (none) {
                none.hidden = shown > 0;
            }
            // The count is a live region: say it once the user pauses, not at every key.
            clearTimeout(timer);
            timer = setTimeout(() => {
                count.textContent = count.dataset.template.replace('{count}', String(shown));
            }, 300);
        };

        input.addEventListener('input', filter);
        search.addEventListener('submit', event => {
            event.preventDefault();
            filter();
        });
    }

    // ---- Photo -----------------------------------------------------------------------------------------------------

    const MAX_SIDE = 800;
    const photo = document.querySelector('input[type=file][data-photo-input]');
    const canResize = typeof window.DataTransfer === 'function' && typeof HTMLCanvasElement !== 'undefined'
        && typeof HTMLCanvasElement.prototype.toBlob === 'function';

    function shrink(file) {
        return new Promise((resolve, reject) => {
            const url = URL.createObjectURL(file);
            const image = new Image();
            image.onload = () => {
                // The browser applies the camera's orientation when it decodes the image.
                const scale = Math.min(1, MAX_SIDE / Math.max(image.naturalWidth, image.naturalHeight));
                const canvas = document.createElement('canvas');
                canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
                canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
                const context = canvas.getContext('2d');
                context.fillStyle = '#fff'; // JPEG has no transparency
                context.fillRect(0, 0, canvas.width, canvas.height);
                context.drawImage(image, 0, 0, canvas.width, canvas.height);
                URL.revokeObjectURL(url);
                canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('toBlob'))), 'image/jpeg', 0.85);
            };
            image.onerror = () => {
                URL.revokeObjectURL(url);
                reject(new Error('decode'));
            };
            image.src = url;
        });
    }

    if (photo && canResize) {
        const form = photo.form;
        const preview = document.querySelector('[data-photo-preview]');
        const info = document.getElementById(photo.dataset.fileInfo);
        const submits = Array.from(form.querySelectorAll('button[type=submit]'));
        let replacing = false;
        let previewUrl = null;

        const clearPreview = () => {
            if (previewUrl) {
                URL.revokeObjectURL(previewUrl);
                previewUrl = null;
            }
            if (preview) {
                preview.replaceChildren();
                preview.hidden = true;
            }
        };

        const busy = on => {
            submits.forEach(button => {
                button.disabled = on;
            });
            form.toggleAttribute('aria-busy', on);
            if (on && info) {
                info.hidden = false;
                info.classList.remove('is-over');
                info.textContent = photo.dataset.resizing;
            }
        };

        // Lets js/forms.js show the file now chosen (name and size) again, without starting this listener over.
        const announce = () => {
            replacing = true;
            try {
                photo.dispatchEvent(new Event('change', {bubbles: true}));
            } finally {
                replacing = false;
            }
        };

        photo.addEventListener('change', () => {
            if (replacing) {
                return; // the change this script fires after putting the smaller file in place
            }
            clearPreview();
            const file = photo.files && photo.files[0];
            if (!file || !/^image\/(jpeg|png)$/.test(file.type)) {
                return;
            }
            busy(true);
            shrink(file).then(blob => {
                const name = file.name.replace(/\.[^.]*$/, '') + '.jpg';
                const transfer = new DataTransfer();
                transfer.items.add(new File([blob], name, {type: 'image/jpeg'}));
                photo.files = transfer.files;
                announce();
                if (preview) {
                    previewUrl = URL.createObjectURL(blob);
                    const image = document.createElement('img');
                    image.src = previewUrl;
                    image.alt = preview.dataset.alt || '';
                    preview.append(image);
                    preview.hidden = false;
                }
            }).catch(() => {
                // Keep the original: the server checks and re-encodes it.
                announce();
            }).finally(() => busy(false));
        });
    }
})();
