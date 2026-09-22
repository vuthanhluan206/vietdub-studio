'use strict';
const $ = id => document.getElementById(id);
let timer;
let publishTimer;
let previewUrl;
let publishPreviewUrl;
let voicePreviewUrl;
let tiktokState = {};
let publishJobId;

for (let i = 0; i < 65; i++) {
  const option = document.createElement('option');
  option.value = i;
  option.textContent = `Giọng ${String(i + 1).padStart(2, '0')}`;
  $('voice-id').append(option);
}

const labels = {
  RECEIVED: 'Đang chờ', DOWNLOADING: 'Đang tải video', EXTRACTING: 'Đang tách âm thanh',
  TRANSCRIBING: 'Đang nhận diện lời nói', TRANSLATING: 'Đang dịch', SPEAKING: 'Đang tạo giọng Việt',
  RENDERING: 'Đang ghép video', COMPLETED: 'Hoàn tất MP4', FAILED: 'Thất bại'
};
const privacyLabels = {
  PUBLIC_TO_EVERYONE: 'Mọi người', MUTUAL_FOLLOW_FRIENDS: 'Bạn bè theo dõi lẫn nhau',
  FOLLOWER_OF_CREATOR: 'Người theo dõi', SELF_ONLY: 'Chỉ mình tôi'
};

async function api(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || body.detail || `Yêu cầu thất bại (HTTP ${response.status}).`);
  }
  return response;
}

function show(id, message, error = false) {
  $(id).textContent = message;
  $(id).className = error ? 'error' : 'success';
}

async function initialize() {
  clearTimeout(timer);
  try {
    const health = await (await api('/api/health')).json();
    $('health').hidden = health.aiConfigured;
    if (!health.aiConfigured) show('health', health.aiProvider === 'local'
      ? 'Thiếu công cụ AI local. Hãy chạy scripts/setup-local-ai.ps1 rồi khởi động lại.'
      : 'Cần cấu hình OPENAI_API_KEY rồi khởi động lại để lồng tiếng.', true);
    $('submit-job').disabled = !health.aiConfigured;
    $('preview-voice').disabled = !health.aiConfigured || health.aiProvider !== 'local';
    $('refresh').disabled = false;
    await refreshTikTokStatus();
    await refreshJobs();
  } catch (error) {
    $('submit-job').disabled = true;
    $('refresh').disabled = true;
    $('connect-tiktok').disabled = true;
    $('health').hidden = false;
    show('health', error.message, true);
  }
}

async function refreshTikTokStatus() {
  try {
    tiktokState = await (await api('/api/tiktok/status')).json();
    $('connect-tiktok').disabled = !tiktokState.configured;
    $('connect-tiktok').textContent = tiktokState.connected ? 'Kết nối lại' : 'Kết nối tài khoản';
    const message = tiktokState.postingEnabled
      ? 'Đã kết nối. Video hoàn tất có thể đăng trực tiếp lên TikTok.'
      : tiktokState.connected
        ? 'Đã kết nối nhưng chưa có quyền đăng video. Hãy kết nối lại và cấp quyền video.publish.'
        : tiktokState.configured
          ? 'Đã cấu hình ứng dụng, chưa kết nối tài khoản TikTok.'
          : 'Chưa cấu hình TikTok Developer App. Bạn vẫn có thể tạo và tải MP4.';
    show('tiktok-status', message);
  } catch (error) {
    tiktokState = {};
    $('connect-tiktok').disabled = true;
    show('tiktok-status', error.message, true);
  }
}

$('preview-voice').addEventListener('click', async () => {
  const button = $('preview-voice');
  button.disabled = true;
  try {
    const blob = await (await api(`/api/voices/${Number($('voice-id').value)}/preview`, { method: 'POST' })).blob();
    if (voicePreviewUrl) URL.revokeObjectURL(voicePreviewUrl);
    voicePreviewUrl = URL.createObjectURL(blob);
    const audio = $('voice-preview'); audio.src = voicePreviewUrl; audio.hidden = false;
    await audio.play().catch(() => {});
  } catch (error) { show('message', error.message, true); }
  finally { button.disabled = false; }
});

$('voice-id').addEventListener('change', () => {
  const audio = $('voice-preview'); audio.pause(); audio.hidden = true; audio.removeAttribute('src');
  if (voicePreviewUrl) URL.revokeObjectURL(voicePreviewUrl);
  voicePreviewUrl = undefined;
});

$('job-form').addEventListener('submit', async event => {
  event.preventDefault();
  const url = $('source-url').value.trim();
  const file = $('source-file').files[0];
  const voiceId = Number($('voice-id').value);
  if (Boolean(url) === Boolean(file)) { show('message', 'Hãy chọn đúng một nguồn: link TikTok hoặc file MP4.', true); return; }
  if (file && file.size > 104857600) { show('message', 'File vượt quá 100 MB.', true); return; }
  $('submit-job').disabled = true;
  try {
    let response;
    if (file) {
      const body = new FormData();
      body.set('file', file); body.set('rightsConfirmed', String($('rights').checked)); body.set('voiceId', String(voiceId));
      response = await api('/api/jobs/upload', { method: 'POST', body });
    } else {
      response = await api('/api/jobs', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url, rightsConfirmed: $('rights').checked, voiceId })
      });
    }
    await response.json();
    show('message', 'Đã nhận video. Hệ thống đang bắt đầu xử lý.');
    await refreshJobs();
  } catch (error) { show('message', error.message, true); }
  finally { $('submit-job').disabled = false; }
});

async function refreshJobs() {
  clearTimeout(timer);
  try {
    const jobs = await (await api('/api/jobs')).json();
    $('jobs').replaceChildren();
    if (!jobs.length) $('jobs').textContent = 'Chưa có video. Thêm link hoặc MP4 trong mục Tạo video mới để bắt đầu.';
    for (const job of jobs.slice(0, 1)) {
      const row = document.createElement('article'); row.className = 'job'; row.dataset.status = job.status;
      const status = document.createElement('strong'); status.textContent = labels[job.status] || job.status;
      const source = document.createElement('p'); source.textContent = job.sourceUrl || 'MP4 từ máy tính';
      const info = document.createElement('small');
      info.textContent = `${new Date(job.createdAt).toLocaleString('vi-VN')} · Giọng ${String(job.voiceId + 1).padStart(2, '0')}`;
      row.append(status, source, info);
      if (job.error) { const error = document.createElement('p'); error.className = 'error'; error.textContent = job.error; row.append(error); }
      const actions = document.createElement('p'); actions.className = 'actions';
      if (job.status === 'COMPLETED') {
        addButton(actions, 'Xem trước', () => openVideo(job.id, false));
        addButton(actions, 'Tải MP4', () => openVideo(job.id, true));
        if (tiktokState.postingEnabled) addButton(actions, 'Đăng lên TikTok', () => openPublish(job.id), 'publish-button');
      }
      if (job.status === 'FAILED') addButton(actions, 'Thử lại', async () => {
        if (!confirm('Thử lại tác vụ từ đầu bằng AI cục bộ?')) return;
        await api(`/api/jobs/${job.id}/retry`, { method: 'POST' }); await refreshJobs();
      });
      row.append(actions); $('jobs').append(row);
    }
    timer = setTimeout(refreshJobs, 3000);
  } catch (error) { show('message', error.message, true); }
}

function addButton(parent, text, action, className = 'secondary') {
  const button = document.createElement('button');
  button.type = 'button'; button.className = className; button.textContent = text;
  button.addEventListener('click', async () => {
    button.disabled = true;
    try { await action(); } catch (error) { show('message', error.message, true); }
    finally { button.disabled = false; }
  });
  parent.append(button);
}

async function openVideo(id, download) {
  const blob = await (await api(`/api/jobs/${id}/file`)).blob();
  const url = URL.createObjectURL(blob);
  if (download) {
    const link = document.createElement('a'); link.href = url; link.download = `dubbed-${id}.mp4`;
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 60000);
  } else {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = url; $('preview').src = url; $('preview').hidden = false;
    $('preview').scrollIntoView({ behavior: 'smooth' });
  }
}

async function openPublish(jobId) {
  show('message', 'Đang lấy thông tin mới nhất từ tài khoản TikTok…');
  const [creator, blob] = await Promise.all([
    api('/api/tiktok/creator').then(response => response.json()),
    api(`/api/jobs/${jobId}/file`).then(response => response.blob())
  ]);
  publishJobId = jobId;
  $('tiktok-form').reset();
  $('post-title').value = 'Video được lồng tiếng Việt bằng AI. #longtieng #tiengviet';
  $('creator-name').textContent = creator.username ? `${creator.nickname} (@${creator.username})` : creator.nickname;
  const privacy = $('privacy-level');
  privacy.replaceChildren(new Option('Chọn quyền riêng tư', ''));
  for (const value of creator.privacyLevelOptions) privacy.add(new Option(privacyLabels[value] || value, value));
  setInteraction('allow-comment', creator.commentDisabled);
  setInteraction('allow-duet', creator.duetDisabled);
  setInteraction('allow-stitch', creator.stitchDisabled);
  if (publishPreviewUrl) URL.revokeObjectURL(publishPreviewUrl);
  publishPreviewUrl = URL.createObjectURL(blob);
  $('tiktok-preview').src = publishPreviewUrl;
  $('commercial-options').hidden = true;
  $('brand-policy').hidden = true;
  show('publish-status', `Tài khoản cho phép video dài tối đa ${creator.maxVideoPostDurationSec} giây.`);
  updatePublishForm();
  $('tiktok-dialog').showModal();
  show('message', '');
}

function setInteraction(id, disabled) {
  const input = $(id); input.checked = false; input.disabled = disabled;
  input.closest('label').classList.toggle('disabled', disabled);
}

function updatePublishForm() {
  const commercial = $('commercial-content').checked;
  $('commercial-options').hidden = !commercial;
  $('your-brand').disabled = !commercial;
  const privatePost = $('privacy-level').value === 'SELF_ONLY';
  $('branded-content').disabled = !commercial || privatePost;
  if (!commercial) { $('your-brand').checked = false; $('branded-content').checked = false; }
  if (privatePost) $('branded-content').checked = false;
  $('brand-policy').hidden = !$('branded-content').checked;
  const disclosureValid = !commercial || $('your-brand').checked || $('branded-content').checked;
  $('submit-publish').disabled = !$('privacy-level').value || !$('publish-consent').checked || !disclosureValid;
}

for (const id of ['privacy-level', 'commercial-content', 'your-brand', 'branded-content', 'publish-consent']) {
  $(id).addEventListener('change', updatePublishForm);
}

$('tiktok-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = $('submit-publish');
  button.disabled = true;
  try {
    const body = {
      title: $('post-title').value, privacyLevel: $('privacy-level').value,
      allowComment: $('allow-comment').checked, allowDuet: $('allow-duet').checked,
      allowStitch: $('allow-stitch').checked, commercialContent: $('commercial-content').checked,
      yourBrand: $('your-brand').checked, brandedContent: $('branded-content').checked,
      consent: $('publish-consent').checked
    };
    show('publish-status', 'Đang tải video lên TikTok…');
    const result = await (await api(`/api/tiktok/publish/${publishJobId}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    })).json();
    show('publish-status', 'TikTok đã nhận video và đang xử lý. Vui lòng chờ…');
    clearTimeout(publishTimer);
    publishTimer = setTimeout(() => pollPublish(result.publishId), 4000);
  } catch (error) {
    show('publish-status', error.message, true);
    updatePublishForm();
  }
});

async function pollPublish(publishId) {
  try {
    const result = await (await api(`/api/tiktok/publish/${encodeURIComponent(publishId)}/status`)).json();
    if (result.status === 'PUBLISH_COMPLETE') {
      show('publish-status', 'Đăng TikTok thành công. Video sẽ xuất hiện trên trang cá nhân sau khi TikTok xử lý xong.');
      return;
    }
    if (result.status === 'FAILED') {
      show('publish-status', `TikTok không đăng được video${result.failReason ? `: ${result.failReason}` : '.'}`, true);
      updatePublishForm();
      return;
    }
    show('publish-status', 'TikTok đang xử lý video. Vui lòng chờ…');
    publishTimer = setTimeout(() => pollPublish(publishId), 5000);
  } catch (error) {
    show('publish-status', error.message, true);
    updatePublishForm();
  }
}

function closePublish() { $('tiktok-dialog').close(); }
$('close-publish').addEventListener('click', closePublish);
$('cancel-publish').addEventListener('click', closePublish);
$('refresh').addEventListener('click', refreshJobs);
$('connect-tiktok').addEventListener('click', async () => {
  const popup = window.open('about:blank', '_blank');
  try {
    const result = await (await api('/api/tiktok/connect', { method: 'POST' })).json();
    if (popup) { popup.opener = null; popup.location.replace(result.authorizationUrl); }
    else window.location.assign(result.authorizationUrl);
    show('tiktok-status', 'Hoàn tất đăng nhập trong tab TikTok vừa mở, sau đó quay lại đây.');
    setTimeout(async () => { await refreshTikTokStatus(); await refreshJobs(); }, 5000);
  } catch (error) { if (popup) popup.close(); show('tiktok-status', error.message, true); }
});

window.addEventListener('focus', async () => {
  if (!tiktokState.configured) return;
  await refreshTikTokStatus();
  await refreshJobs();
});

initialize();
