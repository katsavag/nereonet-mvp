// "Photograph the invoice" (P62, shiporder/invoice.html) and its progress page (shiporder/invoice-reading.html).
// Only enhancements: without it each page goes with its Upload button and the progress page reloads itself.
//  - A photo or file chosen (input.invoice-file) is sent at once, alone (POST its form's action with js=true, field
//    "file", and "replace" for Retake), with "Sending page 2: 60%" and a progress bar; then the page comes back with it
//    as a thumbnail. A photo is made smaller first: at most 2000 px on its long side, JPEG at quality 0.8 (the camera's
//    orientation applied), so a 4 MB photo is about 500 KB on the satellite link; a PDF, a HEIC the browser cannot
//    decode, or a photo that would not get smaller goes as it is. The server's refusal is shown in its words; a
//    dropped connection says so with Try again (the pages already sent are kept on the server).
//  - The Upload buttons are hidden: nothing to press.
//  - The progress page asks /reading/status every 2 seconds, shows its text ("Reading page 2 of 2") and goes on as
//    soon as the reading is done (the meta refresh does the same, more slowly, without the script).
(function () {
    'use strict';

    // ---- The progress page -----------------------------------------------------------------------------------------

    const reading = document.querySelector('[data-invoice-reading]');
    if (reading && typeof fetch === 'function') {
        const text = reading.querySelector('[data-reading-text]');
        const poll = () => {
            fetch(reading.dataset.statusUrl, {headers: {Accept: 'application/json'}, credentials: 'same-origin'})
                .then(response => response.ok ? response.json() : null)
                .then(status => {
                    if (!status) {
                        return;
                    }
                    if (status.text) {
                        text.textContent = status.text;
                    }
                    if (status.done && status.next) {
                        window.location.assign(status.next);
                        return;
                    }
                    setTimeout(poll, 2000);
                })
                .catch(() => setTimeout(poll, 4000)); // a dropped link: try again, the meta refresh also does
        };
        setTimeout(poll, 1500);
        return;
    }

    // ---- The photo page --------------------------------------------------------------------------------------------

    const page = document.querySelector('[data-invoice]');
    if (!page || typeof FormData !== 'function' || typeof XMLHttpRequest !== 'function') {
        return;
    }
    const progress = page.querySelector('[data-invoice-progress]');
    const bar = progress.querySelector('progress');
    const status = progress.querySelector('[role=status]');
    const retry = progress.querySelector('[data-invoice-retry]');
    const maxSize = Number(page.dataset.maxSize) || 0;
    const MAX_SIDE = 2000;
    const QUALITY = 0.8;
    const canResize = typeof HTMLCanvasElement !== 'undefined'
        && typeof HTMLCanvasElement.prototype.toBlob === 'function';
    let busy = false;
    let lastAttempt = null;

    page.querySelectorAll('[data-invoice-upload]').forEach(button => {
        button.hidden = true;
    });

    function show(message, failed, canRetry) {
        progress.hidden = false;
        progress.classList.toggle('is-failed', Boolean(failed));
        status.textContent = message;
        status.setAttribute('role', failed ? 'alert' : 'status');
        bar.hidden = Boolean(failed);
        retry.hidden = !canRetry;
    }

    function setBusy(value) {
        busy = value;
        page.querySelectorAll('input.invoice-file, button').forEach(control => {
            if (control !== retry) {
                control.disabled = value;
            }
        });
        page.toggleAttribute('aria-busy', value);
    }

    // Decodes the photo with the camera's orientation applied: createImageBitmap(imageOrientation: from-image) when
    // the browser has it, else an <img> (browsers apply EXIF orientation to images by default).
    function decode(file) {
        if (typeof createImageBitmap === 'function') {
            return createImageBitmap(file, {imageOrientation: 'from-image'}).catch(() => decodeWithImage(file));
        }
        return decodeWithImage(file);
    }

    function decodeWithImage(file) {
        return new Promise((resolve, reject) => {
            const url = URL.createObjectURL(file);
            const image = new Image();
            image.onload = () => {
                URL.revokeObjectURL(url);
                resolve(image);
            };
            image.onerror = () => {
                URL.revokeObjectURL(url);
                reject(new Error('not decoded'));
            };
            image.src = url;
        });
    }

    // The photo made smaller, as JPEG; the file itself when it cannot be or would not get smaller.
    function shrink(file) {
        if (!canResize || !/^image\//.test(file.type) || file.type === 'image/gif') {
            return Promise.resolve(file);
        }
        return decode(file).then(image => {
            const width = image.width || image.naturalWidth;
            const height = image.height || image.naturalHeight;
            const scale = Math.min(1, MAX_SIDE / Math.max(width, height));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(width * scale));
            canvas.height = Math.max(1, Math.round(height * scale));
            const context = canvas.getContext('2d');
            context.fillStyle = '#fff'; // JPEG has no transparency
            context.fillRect(0, 0, canvas.width, canvas.height);
            context.drawImage(image, 0, 0, canvas.width, canvas.height);
            if (typeof image.close === 'function') {
                image.close();
            }
            return new Promise(resolve => canvas.toBlob(blob => {
                if (!blob || (blob.size >= file.size && file.type === 'image/jpeg')) {
                    resolve(file);
                    return;
                }
                resolve(new File([blob], (file.name || 'page').replace(/\.[^.]*$/, '') + '.jpg', {type: 'image/jpeg'}));
            }, 'image/jpeg', QUALITY));
        }).catch(() => file); // the server checks the original (a HEIC this browser cannot decode, say)
    }

    // Sends one page. Resolves on 204; rejects with {message, retry}.
    function send(form, file, n) {
        return new Promise((resolve, reject) => {
            const data = new FormData();
            form.querySelectorAll('input[type=hidden]').forEach(hidden => data.append(hidden.name, hidden.value));
            data.append('js', 'true');
            data.append('file', file, file.name || 'page.jpg');
            const xhr = new XMLHttpRequest();
            const update = percent => {
                bar.value = percent;
                show(page.dataset.sending.replace('{n}', String(n)).replace('{percent}', String(percent)), false, false);
            };
            xhr.open('POST', form.action);
            xhr.setRequestHeader('Accept', 'text/plain');
            xhr.upload.addEventListener('progress', event => {
                if (event.lengthComputable) {
                    update(Math.min(99, Math.round(event.loaded * 100 / event.total)));
                }
            });
            xhr.addEventListener('load', () => {
                if (xhr.status === 204) {
                    update(100);
                    resolve();
                } else if (xhr.status === 409 || xhr.status === 422) {
                    reject({message: xhr.responseText || page.dataset.failed, retry: false});
                } else if (xhr.responseURL && xhr.responseURL.indexOf('fileTooLarge') >= 0) {
                    reject({message: page.dataset.tooLarge, retry: false});
                } else {
                    // Signed out, an error page: let the server answer the form itself.
                    reject({message: null, retry: false});
                }
            });
            xhr.addEventListener('error', () => reject({message: page.dataset.failed, retry: true}));
            xhr.addEventListener('abort', () => reject({message: page.dataset.failed, retry: true}));
            update(0);
            xhr.send(data);
        });
    }

    async function upload(input) {
        const file = input.files && input.files[0];
        if (!file || busy) {
            return;
        }
        const form = input.form;
        const n = Number(input.dataset.page) || 1;
        lastAttempt = input;
        setBusy(true);
        try {
            show(page.dataset.preparing.replace('{n}', String(n)), false, false);
            const smaller = await shrink(file);
            if (maxSize && smaller.size > maxSize) {
                throw {message: page.dataset.tooLarge, retry: false};
            }
            await send(form, smaller, n);
            window.location.reload(); // the page comes back with the new thumbnail
        } catch (failure) {
            setBusy(false);
            if (failure && failure.message === null) {
                form.submit();
                return;
            }
            show((failure && failure.message) || page.dataset.failed, true, Boolean(failure && failure.retry));
        }
    }

    page.querySelectorAll('input.invoice-file').forEach(input => {
        input.addEventListener('change', () => upload(input));
    });
    retry.addEventListener('click', () => {
        if (lastAttempt) {
            upload(lastAttempt);
        }
    });
})();
