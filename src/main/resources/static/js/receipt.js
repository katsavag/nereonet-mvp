// "Confirm receipt" (P51), the Master's form (shiporder/receipt.html). Loaded by that page only. Only enhancements:
// without it the form works as it is (the files go with Review, and the server checks everything).
//  - "Not delivered" (input[data-receipt-none]) sets the line's quantity to 0 and locks it; unticked, the quantity
//    comes back.
//  - The files chosen (input[data-receipt-files]) are listed with their size, a warning on one larger than 10 MB.
//  - Review with files chosen: each file is sent alone first (POST data-upload-url, field "file"), a JPEG or PNG made
//    smaller in the browser before (a canvas, at most 2000 px on its longest side, JPEG, as js/profile.js does for
//    profile photos; the original when that fails or is not smaller), with a progress bar and "Sending receipt.jpg
//    (1 of 3) · 45%". Then the form is sent without them, a small request. A file the server refuses stops there
//    with its message; a dropped connection stops with "Not sent: the connection dropped..." and the files already
//    sent are not sent again. Review cannot be pressed twice meanwhile. Offline, js/save.js stops the form as usual.
(function () {
    'use strict';

    const form = document.querySelector('form[data-receipt-form]');
    if (!form) {
        return;
    }

    // ---- Not delivered ---------------------------------------------------------------------------------------------

    form.querySelectorAll('[data-receipt-line]').forEach(row => {
        const none = row.querySelector('input[data-receipt-none]');
        const quantity = row.querySelector('input[data-receipt-quantity]');
        if (!none || !quantity) {
            return;
        }
        let before = quantity.value;
        const apply = () => {
            if (none.checked) {
                if (quantity.value !== '0') {
                    before = quantity.value;
                }
                quantity.value = '0';
                quantity.readOnly = true;
            } else {
                if (quantity.readOnly && quantity.value === '0') {
                    quantity.value = before;
                }
                quantity.readOnly = false;
            }
        };
        none.addEventListener('change', apply);
        if (none.checked) {
            apply();
        }
    });

    // ---- The files -------------------------------------------------------------------------------------------------

    const input = form.querySelector('input[type=file][data-receipt-files]');
    const chosen = form.querySelector('[data-receipt-chosen]');
    const progress = form.querySelector('[data-receipt-progress]');
    const uploadUrl = form.dataset.uploadUrl;
    if (!input || !progress || !uploadUrl || typeof FormData !== 'function' || typeof XMLHttpRequest !== 'function') {
        return;
    }
    const bar = progress.querySelector('progress');
    const status = progress.querySelector('[role=status]');
    const maxSize = Number(input.dataset.maxSize) || 0;
    const MAX_SIDE = 2000;
    const canResize = typeof HTMLCanvasElement !== 'undefined' && typeof HTMLCanvasElement.prototype.toBlob === 'function';

    const sent = new WeakSet(); // files the server has, never sent again
    const smaller = new WeakMap(); // file -> the smaller version sent in its place
    let uploading = false;
    let readyToSend = false;

    function size(bytes) {
        if (bytes < 1024) {
            return bytes + ' B';
        }
        if (bytes < 1048576) {
            return (bytes / 1024).toFixed(1) + ' KB';
        }
        return (bytes / 1048576).toFixed(1) + ' MB';
    }

    function files() {
        return Array.from(input.files || []);
    }

    function listChosen() {
        if (!chosen) {
            return;
        }
        chosen.replaceChildren();
        files().forEach(file => {
            const item = document.createElement('li');
            const tooLarge = maxSize && file.size > maxSize && !/^image\/(jpeg|png)$/.test(file.type);
            item.textContent = file.name + ' · ' + size(file.size) + (tooLarge ? ' · ' + input.dataset.tooLarge : '');
            item.classList.toggle('is-over', Boolean(tooLarge));
            item.classList.toggle('is-sent', sent.has(file));
            chosen.append(item);
        });
        chosen.hidden = files().length === 0;
    }

    input.addEventListener('change', listChosen);
    listChosen(); // a choice kept by the browser after going back

    function shrink(file) {
        if (!canResize || !/^image\/(jpeg|png)$/.test(file.type)) {
            return Promise.resolve(file);
        }
        return new Promise(resolve => {
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
                canvas.toBlob(blob => {
                    if (!blob || blob.size >= file.size) {
                        resolve(file); // keep the original: it is smaller, or the browser could not
                        return;
                    }
                    resolve(new File([blob], file.name.replace(/\.[^.]*$/, '') + '.jpg', {type: 'image/jpeg'}));
                }, 'image/jpeg', 0.85);
            };
            image.onerror = () => {
                URL.revokeObjectURL(url);
                resolve(file); // the server checks the original
            };
            image.src = url;
        });
    }

    function show(text, failed) {
        progress.hidden = false;
        progress.classList.toggle('is-failed', Boolean(failed));
        status.textContent = text;
        status.setAttribute('role', failed ? 'alert' : 'status');
    }

    function setButtons(disabled) {
        form.querySelectorAll('button[type=submit]').forEach(button => {
            button.disabled = disabled;
        });
        form.toggleAttribute('aria-busy', disabled);
    }

    // Sends one file. Resolves on 204; rejects with the text to show.
    function upload(file, n, total) {
        return new Promise((resolve, reject) => {
            const data = new FormData();
            form.querySelectorAll('input[type=hidden][name^="_csrf"]').forEach(token => data.append(token.name, token.value));
            data.append('file', file, file.name);
            const xhr = new XMLHttpRequest();
            const label = progress.dataset.text;
            const update = percent => {
                bar.value = percent;
                show(label.replace('{name}', file.name).replace('{n}', String(n)).replace('{total}', String(total))
                    .replace('{percent}', String(percent)));
            };
            xhr.open('POST', uploadUrl);
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
                    reject(xhr.responseText || progress.dataset.failed);
                } else if (xhr.responseURL && xhr.responseURL.indexOf('fileTooLarge') >= 0) {
                    reject(file.name + ': ' + input.dataset.tooLarge);
                } else {
                    // Anything else (signed out, an error page): let the server answer the whole form.
                    reject(null);
                }
            });
            xhr.addEventListener('error', () => reject(progress.dataset.failed));
            xhr.addEventListener('abort', () => reject(progress.dataset.failed));
            update(0);
            xhr.send(data);
        });
    }

    async function sendAll(submitter) {
        uploading = true;
        setButtons(true);
        const pending = files().filter(file => !sent.has(file));
        try {
            if (pending.some(file => canResize && /^image\/(jpeg|png)$/.test(file.type) && !smaller.has(file))) {
                show(progress.dataset.resizing);
            }
            for (let i = 0; i < pending.length; i++) {
                const file = pending[i];
                if (!smaller.has(file)) {
                    smaller.set(file, await shrink(file));
                }
                const toSend = smaller.get(file);
                if (maxSize && toSend.size > maxSize) {
                    throw file.name + ': ' + input.dataset.tooLarge;
                }
                await upload(toSend, i + 1, pending.length);
                sent.add(file);
                listChosen();
            }
        } catch (message) {
            uploading = false;
            setButtons(false);
            if (message === null) {
                readyToSend = true; // the server answers the form, files and all
                form.requestSubmit ? form.requestSubmit(submitter) : form.submit();
                return;
            }
            show(message, true);
            return;
        }
        // Every file is on the server: send the form without them.
        input.value = '';
        listChosen();
        uploading = false;
        setButtons(false);
        readyToSend = true;
        if (form.requestSubmit) {
            form.requestSubmit(submitter && submitter.form === form ? submitter : undefined);
        } else {
            form.submit();
        }
    }

    form.addEventListener('submit', event => {
        if (readyToSend) {
            readyToSend = false;
            return; // js/save.js takes it from here (sent once, the draft noted)
        }
        if (uploading) {
            event.preventDefault();
            return;
        }
        if (navigator.onLine === false) {
            return; // js/save.js stops it and says why
        }
        if (files().every(file => sent.has(file))) {
            input.value = ''; // nothing chosen, or every file already on the server
            return;
        }
        event.preventDefault();
        sendAll(event.submitter);
    });
})();
