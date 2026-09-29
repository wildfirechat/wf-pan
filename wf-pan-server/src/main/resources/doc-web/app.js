// 在线文档页面公用：客户端桥（dsbridge）、登录、接口调用、小工具。不依赖任何第三方脚本。
(function () {
  'use strict';

  // 页面在 /pan/doc/ 下，接口在 /pan/api/v1/ 下（都走同一个入口，路径相对计算，不写死前缀）
  var DOC_BASE = new URL('./', location.href);
  var API_BASE = new URL('../api/v1/', DOC_BASE);

  // ------------------------------------------------------------ 客户端桥
  // 与客户端工作台 webview 的 dsbridge 协议一致：prompt("_dsbridge=方法", JSON{data, _dscbstub})，
  // 异步方法完成时客户端调用 window[_dscbstub](结果)
  var cbSeq = 0;
  var Bridge = {
    available: function () {
      // 野火客户端的 WebView 在 UA 上追加 WF-DSBridge（chat/lib/workspace/webview_support.dart）
      return !!(window._dsbridge || window._dswk || /WF-DSBridge|_dsbridge/.test(navigator.userAgent));
    },
    callSync: function (method, args) {
      var arg = JSON.stringify({ data: args === undefined ? null : args });
      var ret = window._dsbridge ? window._dsbridge.call(method, arg) : prompt('_dsbridge=' + method, arg);
      try { return JSON.parse(ret || '{}'); } catch (e) { return {}; }
    },
    call: function (method, args, timeoutMs) {
      return new Promise(function (resolve, reject) {
        if (!Bridge.available()) { reject(new Error('不在客户端内')); return; }
        var name = '__pandscb' + (++cbSeq);
        var timer = timeoutMs ? setTimeout(function () { delete window[name]; reject(new Error('客户端无响应')); }, timeoutMs) : null;
        window[name] = function (res) {
          if (timer) clearTimeout(timer);
          delete window[name];
          resolve(res);
        };
        var arg = JSON.stringify({ data: args === undefined ? null : args, _dscbstub: name });
        var ret = window._dsbridge ? window._dsbridge.call(method, arg) : prompt('_dsbridge=' + method, arg);
        // 同步返回值对异步方法没有意义：dsbridge_flutter 调异步方法成功时同步返回的也是 code -1
        // （只有同步方法才改成 0），结果从回调来。只有客户端确实没有这个方法时才判"不支持"。
        try {
          var r = JSON.parse(ret || '{}');
          if (r && r.code === -1 && !Bridge.has(method)) {
            if (timer) clearTimeout(timer);
            delete window[name];
            reject(new Error('客户端不支持 ' + method));
          }
        } catch (e) { /* 正常情况下异步方法同步返回空 */ }
      });
    },
    /** 调一个会多次回调的异步方法：客户端用 setProgressData 回结果，回调常驻（再调同一方法就换成新的回调） */
    listen: function (method, args, onData) {
      var name = '__pandsl_' + method.replace(/\W/g, '_');
      window[name] = onData;
      var arg = JSON.stringify({ data: args === undefined ? null : args, _dscbstub: name });
      if (window._dsbridge) window._dsbridge.call(method, arg); else prompt('_dsbridge=' + method, arg);
    },
    has: function (method) {
      if (!Bridge.available()) return false;
      var r = Bridge.callSync('_dsb.hasNativeMethod', { name: method, type: 'all' });
      return r && r.data === true;
    }
  };
  // 客户端里有自己的标题栏和返回键，页面上重复的标题、「返回」据此藏掉（本脚本在 <head> 里加载，不闪）
  document.documentElement.classList.toggle('in-client', Bridge.available());

  var headerSupported = null;
  /**
   * 把标题、只读说明和操作按钮交给客户端标题栏去画（新版客户端有 setPageHeader），页面就不用自己再占一栏。
   * header = {title, subtitle, actions: [{id, text, icon, primary}]}，每次整体替换；title 省略时客户端保留原标题。
   * 点按钮时回调 onAction(按钮 id)。返回 false 表示客户端不支持（浏览器里、旧版客户端），由页面自己画。
   */
  function pageHeader(header, onAction) {
    if (headerSupported === null) headerSupported = Bridge.has('setPageHeader');
    if (!headerSupported) return false;
    Bridge.listen('setPageHeader', header, function (id) { onAction(String(id)); });
    return true;
  }

  // ------------------------------------------------------------ 登录与接口
  var loginPromise = null;
  function login() {
    if (!loginPromise) {
      loginPromise = Bridge.call('getAuthCode', { appId: 'admin', appType: 2 }, 20000).then(function (res) {
        if (!res || res.code !== 0 || !res.data) throw new Error('获取登录凭证失败（' + (res && res.code) + '）');
        return fetch(new URL('session', DOC_BASE), {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ authCode: res.data })
        }).then(function (r) { return r.json(); });
      }).then(function (r) {
        if (!r || r.code !== 0) throw new Error((r && r.message) || '登录失败');
        return r.data;
      }).finally(function () { setTimeout(function () { loginPromise = null; }, 0); });
    }
    return loginPromise;
  }

  function api(path, body, retried) {
    return fetch(new URL(path, API_BASE), {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Pan-Web': '1' },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().catch(function () { throw new Error('服务异常（HTTP ' + r.status + '）'); });
    }).then(function (r) {
      if (r.code === 1001 || r.code === 1002 || r.code === 1004) {
        if (retried) throw new Error('登录失效，请重新打开');
        if (!Bridge.available()) {
          var e = new Error('请在客户端中打开'); e.notInClient = true; throw e;
        }
        return login().then(function () { return api(path, body, true); });
      }
      if (r.code !== 0) throw new Error(r.message || '请求失败');
      return r.data;
    });
  }

  // ------------------------------------------------------------ 小工具
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function ext(name) { var i = (name || '').lastIndexOf('.'); return i < 0 ? '' : name.slice(i + 1).toLowerCase(); }
  var KIND = {
    word: ['doc', 'docx', 'docm', 'dot', 'dotx', 'odt', 'ott', 'rtf', 'txt', 'wps', 'wpt'],
    cell: ['xls', 'xlsx', 'xlsm', 'xlt', 'xltx', 'ods', 'ots', 'csv', 'et', 'ett'],
    slide: ['ppt', 'pptx', 'pptm', 'pot', 'potx', 'pps', 'ppsx', 'odp', 'otp', 'dps', 'dpt'],
    pdf: ['pdf']
  };
  function kind(name) {
    var e = ext(name);
    for (var k in KIND) if (KIND[k].indexOf(e) >= 0) return k;
    return 'other';
  }
  var ICON_SIZE = { sm: [20, 24], md: [26, 31], lg: [32, 38] };
  /** 折角的文件图标，颜色按类型（样式见 app.css 的 .ficon）；size: sm / md（默认）/ lg */
  function icon(name, size) {
    var k = kind(name);
    var t = { word: 'W', cell: 'X', slide: 'P', pdf: 'PDF', other: (ext(name) || '?').slice(0, 3).toUpperCase() }[k];
    var wh = ICON_SIZE[size] || ICON_SIZE.md;
    return '<svg class="ficon ' + k + '" width="' + wh[0] + '" height="' + wh[1] + '" viewBox="0 0 30 36" aria-hidden="true">' +
      '<path class="b" d="M4 0h15l11 11v21a4 4 0 0 1-4 4H4a4 4 0 0 1-4-4V4a4 4 0 0 1 4-4z"/>' +
      '<path d="M19 0l11 11h-7a4 4 0 0 1-4-4z" fill="#fff" fill-opacity=".45"/>' +
      '<text x="15" y="29" text-anchor="middle" font-size="' + (t.length > 1 ? 8 : 10) + '" font-weight="700" fill="#fff">' +
      esc(t) + '</text></svg>';
  }
  function fmtTime(s) {
    if (!s) return '';
    var d = new Date(String(s).replace(' ', 'T'));
    if (isNaN(d)) return String(s);
    var now = new Date();
    var p = function (n) { return n < 10 ? '0' + n : '' + n; };
    var hm = p(d.getHours()) + ':' + p(d.getMinutes());
    if (d.toDateString() === now.toDateString()) return '今天 ' + hm;
    if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hm;
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
  }
  function fmtSize(n) {
    if (!(n >= 0)) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }
  var PERM = { VIEW: '可查看', EDIT: '可编辑' };
  function toast(msg, ms) {
    var el = document.createElement('div');
    el.className = 'toast';
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, ms || 2600);
  }
  function isMobile() {
    var q = new URLSearchParams(location.search).get('platform');
    if (q) return q === 'mobile';
    return /Android|iPhone|iPad|iPod|HarmonyOS|OpenHarmony|Mobile/i.test(navigator.userAgent);
  }

  /** 打开一个文档：在客户端里交给客户端开新页签/新页面（每个页面各绑各的桥），否则本页跳转 */
  function openDoc(fileId) {
    var url = new URL('open?fileId=' + encodeURIComponent(fileId), DOC_BASE).href;
    if (Bridge.available()) {
      Bridge.callSync('openUrl', url);
    } else {
      location.href = url;
    }
  }

  /** 打开下载地址：在客户端里交给客户端，否则新窗口 */
  function openLink(url) {
    if (Bridge.available()) Bridge.callSync('openUrl', url);
    else window.open(url, '_blank', 'noopener');
  }

  window.PanDoc = {
    Bridge: Bridge, pageHeader: pageHeader, api: api, login: login, esc: esc, ext: ext, kind: kind, icon: icon,
    fmtTime: fmtTime, fmtSize: fmtSize, PERM: PERM, toast: toast, isMobile: isMobile,
    openDoc: openDoc, openLink: openLink, DOC_BASE: DOC_BASE
  };
})();
