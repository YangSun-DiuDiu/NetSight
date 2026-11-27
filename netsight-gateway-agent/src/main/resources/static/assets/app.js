/* NetSight 边缘网关本地管理页 - 交互逻辑（原生 JS，无第三方依赖） */
var CURRENT_PAGE = 'overview';
var OVERVIEW = null;

document.addEventListener('DOMContentLoaded', function () {
  bindMenu();
  loadOverview();
  setInterval(loadOverview, 15000); // 15s 自动刷新总览
});

function bindMenu() {
  document.querySelectorAll('.menu-item').forEach(function (el) {
    el.addEventListener('click', function () {
      document.querySelectorAll('.menu-item').forEach(function (m) { m.classList.remove('active'); });
      el.classList.add('active');
      var page = el.getAttribute('data-page');
      CURRENT_PAGE = page;
      document.querySelectorAll('.page').forEach(function (p) { p.style.display = 'none'; });
      document.getElementById('page-' + page).style.display = 'block';
      var titles = { overview: '状态总览', devices: '设备管理', video: '视频预览', links: '链路设置', cloud: '云端设置', system: '系统' };
      document.getElementById('pageTitle').textContent = titles[page] || '状态总览';
      if (page === 'devices') loadDevices();
      if (page === 'video') loadVideoPage();
      if (page === 'links') loadLinks();
      if (page === 'cloud') loadCloudCfg();
      if (page === 'system') loadLogs();
    });
  });
}

function api(path, opts) {
  opts = opts || {};
  return fetch(path, opts).then(function (r) { return r.json(); });
}

function toast(msg, ok) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.className = 'toast ' + (ok ? 'ok' : 'err');
  t.style.display = 'block';
  setTimeout(function () { t.style.display = 'none'; }, 3000);
}

/* ===== 状态总览 ===== */
function loadOverview() {
  api('/local/api/overview').then(function (data) {
    if (data.code !== 200) { return handleAuthError(data); }
    OVERVIEW = data.data;
    var gw = data.data.gateway, stats = data.data.stats, dev = data.data.device;
    document.getElementById('gatewayName').textContent = gw.gatewayName || gw.gatewayCode || '边缘网关';
    document.getElementById('verText').textContent = 'v1.0.0';
    var dot = document.getElementById('cloudDot');
    var txt = document.getElementById('cloudText');
    dot.className = 'dot ' + (gw.cloudConnected ? 'ok' : 'err');
    txt.textContent = gw.cloudConnected ? '云端已连接' : '云端离线';
    document.getElementById('linkText').textContent = gw.currentLink || '-';

    document.getElementById('devTotal').textContent = dev.total;
    document.getElementById('devOnline').textContent = dev.online;
    document.getElementById('devOffline').textContent = dev.offline;
    document.getElementById('devLine').textContent = dev.lineAbnormal;

    document.getElementById('gwDesc').innerHTML =
      kv('网关编码', gw.gatewayCode) + kv('网关名称', gw.gatewayName) +
      kv('版本', gw.version) + kv('最近心跳', gw.lastHeartbeatTime) +
      kv('最近快照', gw.lastSnapshotTime) + kv('最近错误', gw.lastError || '-');
    document.getElementById('statDesc').innerHTML =
      kv('心跳次数', stats.heartbeatCount) + kv('快照成功', stats.snapshotCount) +
      kv('快照失败', stats.snapshotFailCount) + kv('告警转发', stats.alertForwardCount) +
      kv('告警失败', stats.alertFailCount) + kv('补传队列', stats.cacheQueueSize);
    document.getElementById('cloudDesc').innerHTML =
      kv('云端地址', data.data.cloud.baseUrl) + kv('租户 ID', data.data.cloud.tenantId) +
      kv('心跳周期', data.data.cloud.heartbeatInterval + 's') + kv('快照周期', data.data.cloud.statusInterval + 's');
    document.getElementById('syncDesc').innerHTML = kv('最近同步', stats.lastSyncResult || '-');
  }).catch(function (e) {
    toast('加载失败: ' + e.message, false);
  });
}

function kv(k, v) {
  return '<div class="row"><span class="k">' + k + '</span><span class="v">' + (v === undefined || v === null ? '-' : v) + '</span></div>';
}

function handleAuthError(data) {
  if (data.code === 401) { location.href = '/login.html'; return true; }
  if (data.code === 4002) { showPwdDialog(true); return true; }
  toast(data.msg || '请求失败', false);
  return true;
}

/* ===== 设备管理 ===== */
var EDIT_DEVICE_CODE = null;

function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function loadDevices() {
  api('/local/api/devices').then(function (data) {
    if (data.code !== 200) { return handleAuthError(data); }
    var meta = data.data || {};
    var items = meta.items || [];
    document.getElementById('devMeta').textContent =
      'revision ' + (meta.revision || '-') + ' · 设备 ' + items.length + ' 台 · 同步 ' + (meta.syncStatus || '-') +
      ' · 最近 ' + (meta.lastSyncAt || '-');
    document.getElementById('syncResult').textContent = meta.nextRetryAt && meta.nextRetryAt !== '-'
      ? '下次重试：' + meta.nextRetryAt : '';
    var tb = document.getElementById('devTable');
    if (items.length === 0) {
      tb.innerHTML = '<tr><td colspan="8" class="empty">暂无设备，请点击“新增设备”录入</td></tr>';
      return;
    }
    tb.innerHTML = items.map(function (d) {
      var badge = '<span class="badge gray">未采集</span>';
      if (d.up === true && d.lineAbnormal === false) badge = '<span class="badge ok">在线</span>';
      else if (d.up === true && d.lineAbnormal === true) badge = '<span class="badge warn">链路异常</span>';
      else if (d.up === false) badge = '<span class="badge err">离线</span>';
      return '<tr><td>' + esc(d.deviceCode) + '</td><td>' + esc(d.deviceName || '-') + '</td><td>' +
        esc(d.deviceIp || '-') + '</td><td>' + esc(d.deviceType || '-') + '</td><td>' +
        esc(d.location || '-') + '</td><td>' + badge + '</td><td>' + esc(d.collectedAt || '-') + '</td>' +
        '<td><button class="btn" onclick="openDeviceDialog(\'' + esc(d.deviceCode) + '\')">编辑</button> ' +
        '<button class="btn danger" onclick="deleteDevice(\'' + esc(d.deviceCode) + '\',\'' + esc(d.deviceName || '') + '\')">删除</button></td></tr>';
    }).join('');
  }).catch(function (e) { toast('加载设备失败: ' + e.message, false); });
}

function openDeviceDialog(code) {
  EDIT_DEVICE_CODE = code || null;
  document.getElementById('devTitle').textContent = code ? '编辑设备' : '新增设备';
  document.getElementById('devCode').value = code || '';
  document.getElementById('devCode').disabled = !!code;
  ['devName', 'devIp', 'devLocation', 'devBrand', 'devModel', 'devWarranty'].forEach(function (id) {
    document.getElementById(id).value = '';
  });
  document.getElementById('devType').value = 'camera';
  if (code) {
    api('/local/api/devices').then(function (data) {
      var d = (data.data.items || []).filter(function (x) { return x.deviceCode === code; })[0];
      if (!d) { toast('设备不存在', false); return; }
      document.getElementById('devName').value = d.deviceName || '';
      document.getElementById('devIp').value = d.deviceIp || '';
      document.getElementById('devType').value = d.deviceType || 'camera';
      document.getElementById('devLocation').value = d.location || '';
      document.getElementById('devBrand').value = d.brand || '';
      document.getElementById('devModel').value = d.model || '';
      document.getElementById('devWarranty').value = d.warrantyExpire || '';
    });
  }
  document.getElementById('devMask').classList.add('show');
}

function hideDeviceDialog() {
  document.getElementById('devMask').classList.remove('show');
}

function saveDevice() {
  var payload = {
    deviceCode: document.getElementById('devCode').disabled ? EDIT_DEVICE_CODE : document.getElementById('devCode').value.trim(),
    deviceName: document.getElementById('devName').value.trim(),
    deviceIp: document.getElementById('devIp').value.trim(),
    deviceType: document.getElementById('devType').value,
    location: document.getElementById('devLocation').value.trim(),
    brand: document.getElementById('devBrand').value.trim(),
    model: document.getElementById('devModel').value.trim(),
    warrantyExpire: document.getElementById('devWarranty').value.trim() || null
  };
  var method = EDIT_DEVICE_CODE ? 'PUT' : 'POST';
  var path = EDIT_DEVICE_CODE ? '/local/api/devices/' + encodeURIComponent(EDIT_DEVICE_CODE) : '/local/api/devices';
  var btn = document.getElementById('devSaveBtn');
  btn.disabled = true;
  api(path, { method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
    .then(function (data) {
      if (data.code !== 200) { toast(data.msg || '保存失败', false); return; }
      toast(EDIT_DEVICE_CODE ? '设备已更新' : '设备已新增', true);
      hideDeviceDialog();
      loadDevices();
      loadOverview();
    })
    .catch(function (e) { toast('保存失败: ' + e.message, false); })
    .finally(function () { btn.disabled = false; });
}

function deleteDevice(code, name) {
  api('/local/api/devices').then(function (data) {
    var items = (data.data && data.data.items) || [];
    var isLast = items.length === 1;
    var msg = '确认删除设备【' + name + '】？';
    if (isLast) msg += '\n这是最后一台设备，删除后清单为空，需要二次确认。';
    if (!confirm(msg)) return;
    if (isLast && !confirm('再次确认：删除后将清空全部设备并同步到云端。')) return;
    api('/local/api/devices/' + encodeURIComponent(code) + '?confirmEmpty=' + isLast, { method: 'DELETE' })
      .then(function (res) {
        if (res.code !== 200) { toast(res.msg || '删除失败', false); return; }
        toast('设备已删除', true);
        loadDevices();
        loadOverview();
      }).catch(function (e) { toast('删除失败: ' + e.message, false); });
  });
}

function clearAllDevices() {
  var phrase = prompt('输入“清空全部”确认清空本地设备清单并同步云端：');
  if (phrase !== '清空全部') { toast('确认短语不正确', false); return; }
  api('/local/api/devices/clear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirm: phrase })
  }).then(function (data) {
    if (data.code !== 200) { toast(data.msg || '清空失败', false); return; }
    toast('清单已清空', true);
    loadDevices();
    loadOverview();
  }).catch(function (e) { toast('清空失败: ' + e.message, false); });
}

function syncDevices(btn) {
  if (btn) btn.disabled = true;
  api('/local/api/devices/sync', { method: 'POST' }).then(function (data) {
    if (data.code !== 200) { handleAuthError(data); return; }
    toast('同步已触发', true);
    setTimeout(loadDevices, 2000);
    loadOverview();
  }).catch(function (e) { toast('同步失败: ' + e.message, false); })
    .finally(function () { if (btn) btn.disabled = false; });
}

/* ===== 链路设置 ===== */
function loadLinks() {
  api('/local/api/links').then(function (data) {
    if (data.code !== 200) { return handleAuthError(data); }
    var tb = document.getElementById('linkTable');
    if (!data.data || data.data.length === 0) {
      tb.innerHTML = '<tr><td colspan="3" class="empty">未配置多链路</td></tr>';
      return;
    }
    var map = { wired: '有线网络', wifi: 'WiFi', '4g5g': '4G/5G' };
    tb.innerHTML = data.data.map(function (l) {
      return '<tr><td>' + (map[l.type] || l.type) + '</td><td>' + l.priority + '</td><td>' +
        (l.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge gray">停用</span>') + '</td></tr>';
    }).join('');
  }).catch(function (e) { toast('加载链路失败: ' + e.message, false); });
}

/* ===== 云端设置 ===== */
function loadCloudCfg() {
  api('/local/api/overview').then(function (data) {
    if (data.code !== 200) { return handleAuthError(data); }
    var c = data.data.cloud;
    document.getElementById('cloudCfg').innerHTML =
      kv('云端入口', c.baseUrl) + kv('租户 ID', c.tenantId) +
      kv('心跳周期', c.heartbeatInterval + ' 秒') + kv('快照周期', c.statusInterval + ' 秒') +
      '<div class="row"><span class="k">网关 Token</span><span class="v">******（脱敏，云端管理页重置）</span></div>' +
      '<div class="row"><span class="k">配置来源</span><span class="v">云端下发（mapping.json/Token）· 本地仅查看</span></div>';
  }).catch(function (e) { toast('加载失败: ' + e.message, false); });
}

/* ===== 系统 ===== */
function loadLogs() {
  api('/local/api/logs?lines=300').then(function (data) {
    if (data.code !== 200) { return handleAuthError(data); }
    document.getElementById('logBox').textContent = data.data || '(空)';
  }).catch(function (e) { toast('加载日志失败: ' + e.message, false); });
}

function doReloadCfg() {
  api('/local/api/sync', { method: 'POST' }).then(function (data) {
    toast((data.code === 200 ? '配置已重新加载：' : '') + (data.data || data.msg), data.code === 200);
    loadOverview();
  }).catch(function (e) { toast('失败: ' + e.message, false); });
}

function doRestart() {
  if (!confirm('确认重启 nams-agent 服务？重启期间上报将中断数秒。')) return;
  api('/local/api/restart', { method: 'POST' }).then(function (data) {
    toast(data.msg || data.data, data.code === 200);
  }).catch(function (e) { toast('失败: ' + e.message, false); });
}

function doLogout() {
  api('/local/api/logout', { method: 'POST' }).then(function () {
    location.href = '/login.html';
  }).catch(function () { location.href = '/login.html'; });
}

/* ===== 修改密码 ===== */
function showPwdDialog(force) {
  document.getElementById('pwdMask').classList.add('show');
  document.getElementById('pwdTitle').textContent = force ? '请先修改默认密码' : '修改本地管理员密码';
  document.getElementById('pwdCancel').style.display = force ? 'none' : '';
  document.getElementById('oldPwd').value = '';
  document.getElementById('newPwd').value = '';
  document.getElementById('newPwd2').value = '';
  if (force) document.getElementById('oldPwd').focus();
}

function hidePwdDialog() {
  document.getElementById('pwdMask').classList.remove('show');
}

function doChangePwd() {
  var oldPwd = document.getElementById('oldPwd').value;
  var newPwd = document.getElementById('newPwd').value;
  var newPwd2 = document.getElementById('newPwd2').value;
  if (!oldPwd || !newPwd) { toast('请填写完整', false); return; }
  if (newPwd.length < 8) { toast('新密码至少 8 位', false); return; }
  if (newPwd !== newPwd2) { toast('两次新密码不一致', false); return; }
  api('/local/api/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ oldPwd: oldPwd, newPwd: newPwd })
  }).then(function (data) {
    if (data.code === 200) {
      toast('密码已修改', true);
      hidePwdDialog();
    } else {
      toast(data.msg || '修改失败', false);
    }
  }).catch(function (e) { toast('失败: ' + e.message, false); });
}

/* ===== 视频预览 ===== */
var VIDEO_DEVICES = [];
var VIDEO_ENDPOINTS = [];
var VIDEO_CFG_CODE = null;
var VIDEO_CURRENT_CODE = null;
var VIDEO_CURRENT_PROFILE = 'main';
var FLV_PLAYER = null;
var VIDEO_SESSION_ID = null;

function loadVideoPage() {
  Promise.all([api('/local/api/devices'), api('/local/api/video/endpoints'), api('/local/api/video/health')])
    .then(function (res) {
      var devRes = res[0], epRes = res[1], healthRes = res[2];
      if (devRes.code !== 200) { return handleAuthError(devRes); }
      VIDEO_DEVICES = (devRes.data.items || []).filter(function (d) {
        return d.deviceType === 'camera' || d.deviceType === 'nvr';
      });
      VIDEO_ENDPOINTS = epRes.code === 200 ? (epRes.data || []) : [];
      var health = healthRes.code === 200 ? healthRes.data : {};
      document.getElementById('videoMeta').textContent =
        '已接入 ' + VIDEO_ENDPOINTS.filter(function (e) { return e.configured; }).length + ' 台 · ' +
        '活动预览 ' + (health.activeSessions || 0) + '/' + (health.maxSessions || 8) + ' 路 · ' +
        (health.zlmHealthy ? '媒体服务正常' : '媒体服务不可用') +
        (health.configured === false ? '（未配置 video-settings.json）' : '');
      var tb = document.getElementById('videoTable');
      if (VIDEO_DEVICES.length === 0) {
        tb.innerHTML = '<tr><td colspan="7" class="empty">暂无摄像头/NVR，请先在“设备管理”录入</td></tr>';
        return;
      }
      tb.innerHTML = VIDEO_DEVICES.map(function (d) {
        var ep = videoConfigFor(d.deviceCode);
        var badge = ep && ep.configured
          ? '<span class="badge ok">已配置' + (ep.enabled ? '' : '·停用') + '</span>'
          : '<span class="badge gray">未配置</span>';
        var buttons = '<button class="btn" onclick="openVideoConfig(\'' + esc(d.deviceCode) + '\')">配置</button>';
        if (ep && ep.configured && ep.enabled) {
          buttons += ' <button class="btn primary" onclick="startVideoPreview(\'' + esc(d.deviceCode) + '\',\'main\')">主码流</button>';
          if (ep.vendor !== 'custom' || ep.subPath) {
            buttons += ' <button class="btn" onclick="startVideoPreview(\'' + esc(d.deviceCode) + '\',\'sub\')">子码流</button>';
          }
        }
        return '<tr><td>' + esc(d.deviceCode) + '</td><td>' + esc(d.deviceName || '-') + '</td><td>' +
          esc(d.deviceIp || '-') + '</td><td>' + esc(d.brand || '-') + '</td><td>' +
          esc(d.deviceType) + '</td><td>' + badge + '</td><td>' + buttons + '</td></tr>';
      }).join('');
    }).catch(function (e) { toast('加载视频列表失败: ' + e.message, false); });
}

function videoConfigFor(code) {
  return VIDEO_ENDPOINTS.filter(function (e) { return e.deviceCode === code; })[0] || null;
}

function openVideoConfig(code) {
  VIDEO_CFG_CODE = code;
  var d = VIDEO_DEVICES.filter(function (x) { return x.deviceCode === code; })[0];
  var ep = videoConfigFor(code);
  if (!d) { toast('设备不存在', false); return; }
  document.getElementById('videoCfgTitle').textContent = '视频接入配置 · ' + (d.deviceName || code);
  document.getElementById('vcDevice').value = code;
  document.getElementById('vcEnabled').checked = ep ? !!ep.enabled : true;
  document.getElementById('vcHost').value = ep ? (ep.host || '') : (d.deviceIp || '');
  document.getElementById('vcPort').value = ep ? (ep.port || 554) : 554;
  document.getElementById('vcChannel').value = ep ? (ep.channel || 1) : 1;
  document.getElementById('vcUsername').value = ep ? (ep.username || '') : '';
  document.getElementById('vcPassword').value = '';
  document.getElementById('vcPassword').placeholder = ep && ep.passwordSet ? '已保存，不修改则留空' : '未设置';
  document.getElementById('vcMainPath').value = ep ? (ep.mainPath || '') : '';
  document.getElementById('vcSubPath').value = ep ? (ep.subPath || '') : '';
  document.getElementById('vcTransport').value = ep ? (ep.transport || 'tcp') : 'tcp';
  var brand = (d.brand || '').toLowerCase();
  document.getElementById('vcVendor').value = ep ? (ep.vendor || inferVendor(brand)) : inferVendor(brand);
  document.getElementById('vcDeleteBtn').style.display = ep ? '' : 'none';
  onVendorChange();
  document.getElementById('videoCfgMask').classList.add('show');
}

function inferVendor(brand) {
  if (brand.indexOf('hik') >= 0 || brand.indexOf('海康') >= 0) return 'hikvision';
  if (brand.indexOf('dahua') >= 0 || brand.indexOf('大华') >= 0) return 'dahua';
  return 'custom';
}

function onVendorChange() {
  var v = document.getElementById('vcVendor').value;
  var main = document.getElementById('vcMainPath');
  var sub = document.getElementById('vcSubPath');
  if (v === 'hikvision') {
    main.placeholder = '/Streaming/Channels/101（留空自动生成）';
    sub.placeholder = '/Streaming/Channels/102（留空自动生成）';
  } else if (v === 'dahua') {
    main.placeholder = '/cam/realmonitor?channel=1&subtype=0（留空自动生成）';
    sub.placeholder = '/cam/realmonitor?channel=1&subtype=1（留空自动生成）';
  } else {
    main.placeholder = '/live/channel1';
    sub.placeholder = '/live/channel1-sub';
  }
}

function hideVideoCfgDialog() {
  document.getElementById('videoCfgMask').classList.remove('show');
}

function saveVideoConfig() {
  if (!VIDEO_CFG_CODE) return;
  var payload = {
    enabled: document.getElementById('vcEnabled').checked,
    vendor: document.getElementById('vcVendor').value,
    host: document.getElementById('vcHost').value.trim(),
    port: parseInt(document.getElementById('vcPort').value, 10) || 554,
    channel: parseInt(document.getElementById('vcChannel').value, 10) || 1,
    username: document.getElementById('vcUsername').value.trim(),
    password: document.getElementById('vcPassword').value,
    mainPath: document.getElementById('vcMainPath').value.trim(),
    subPath: document.getElementById('vcSubPath').value.trim(),
    transport: document.getElementById('vcTransport').value
  };
  api('/local/api/video/endpoints/' + encodeURIComponent(VIDEO_CFG_CODE), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }).then(function (data) {
    if (data.code !== 200) { toast(data.msg || '保存失败', false); return; }
    toast('视频配置已保存', true);
    hideVideoCfgDialog();
    loadVideoPage();
  }).catch(function (e) { toast('保存失败: ' + e.message, false); });
}

function deleteVideoConfig() {
  if (!VIDEO_CFG_CODE) return;
  if (!confirm('确认删除该设备的视频接入配置？')) return;
  api('/local/api/video/endpoints/' + encodeURIComponent(VIDEO_CFG_CODE), { method: 'DELETE' })
    .then(function (data) {
      if (data.code !== 200) { toast(data.msg || '删除失败', false); return; }
      toast('视频配置已删除', true);
      hideVideoCfgDialog();
      loadVideoPage();
    }).catch(function (e) { toast('删除失败: ' + e.message, false); });
}

function startVideoPreview(code, profile) {
  var ep = videoConfigFor(code);
  if (!ep || !ep.configured) {
    toast('请先配置视频接入', false);
    openVideoConfig(code);
    return;
  }
  VIDEO_CURRENT_CODE = code;
  VIDEO_CURRENT_PROFILE = profile;
  document.getElementById('videoTitle').textContent =
    (ep.deviceName || code) + ' · ' + (profile === 'sub' ? '子码流' : '主码流');
  document.getElementById('videoStatus').textContent = '正在拉起 RTSP 流…（首次约 1-3 秒）';
  document.getElementById('btnMain').className = 'btn' + (profile === 'main' ? ' primary' : '');
  document.getElementById('btnSub').className = 'btn' + (profile === 'sub' ? ' primary' : '');
  document.getElementById('videoMask').classList.add('show');
  closeVideoSession();
  api('/local/api/video/preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceCode: code, profile: profile })
  }).then(function (data) {
    if (data.code !== 200) {
      document.getElementById('videoStatus').textContent = data.msg || '视频启动失败';
      return;
    }
    VIDEO_SESSION_ID = data.data.sessionId;
    playFlv(data.data.playUrl);
  }).catch(function (e) {
    document.getElementById('videoStatus').textContent = '视频启动失败: ' + e.message;
  });
}

function playFlv(url) {
  destroyFlv();
  try {
    var lib = (typeof mpegts !== 'undefined' && mpegts.isSupported()) ? mpegts : null;
    if (!lib) {
      document.getElementById('videoStatus').textContent = '当前浏览器不支持 HTTP-FLV 播放';
      return;
    }
    FLV_PLAYER = lib.createPlayer(
      { type: 'flv', url: url, isLive: true, hasAudio: true },
      { enableWorker: false, enableStashBuffer: false, stashInitialSize: 128 }
    );
    FLV_PLAYER.attachMediaElement(document.getElementById('videoPlayer'));
    FLV_PLAYER.on(lib.Events.ERROR, function (type, detail) {
      document.getElementById('videoStatus').textContent = '播放失败：' + detail;
    });
    FLV_PLAYER.on(lib.Events.METADATA_ARRIVED, function () {
      document.getElementById('videoStatus').textContent = '播放中';
    });
    FLV_PLAYER.load();
    var p = FLV_PLAYER.play();
    if (p && p.catch) {
      p.catch(function () {
        document.getElementById('videoStatus').textContent = '已连接，点击视频区域允许播放';
      });
    }
  } catch (e) {
    document.getElementById('videoStatus').textContent = '播放器初始化失败：' + (e && e.message ? e.message : e);
    destroyFlv();
  }
}

function destroyFlv() {
  if (FLV_PLAYER) {
    try { FLV_PLAYER.pause(); FLV_PLAYER.unload(); FLV_PLAYER.detachMediaElement(); FLV_PLAYER.destroy(); } catch (e) {}
    FLV_PLAYER = null;
  }
}

function closeVideoSession() {
  if (VIDEO_SESSION_ID) {
    var id = VIDEO_SESSION_ID;
    VIDEO_SESSION_ID = null;
    api('/local/api/video/session/' + encodeURIComponent(id), { method: 'DELETE' }).catch(function () {});
  }
  destroyFlv();
}

function closeVideoDialog() {
  closeVideoSession();
  document.getElementById('videoStatus').textContent = '已关闭';
  document.getElementById('videoMask').classList.remove('show');
}

function switchVideoProfile(profile) {
  if (!VIDEO_CURRENT_CODE || profile === VIDEO_CURRENT_PROFILE) return;
  startVideoPreview(VIDEO_CURRENT_CODE, profile);
}

window.addEventListener('beforeunload', function () {
  if (VIDEO_SESSION_ID) {
    fetch('/local/api/video/session/' + encodeURIComponent(VIDEO_SESSION_ID),
      { method: 'DELETE', keepalive: true }).catch(function () {});
  }
});
