// Makes the Fire TV's USB webcam available to the page.
// The Fire TV web engine can't see USB webcams, so when the normal camera request
// finds nothing, this draws frames from the app's native camera (window.FireCam)
// onto a canvas and hands the page that canvas as its camera stream.
(function () {
  if (window.__fireCamShim || !window.FireCam || !navigator.mediaDevices) return;
  window.__fireCamShim = true;
  var md = navigator.mediaDevices;
  var origGUM = md.getUserMedia.bind(md);
  var origEnum = md.enumerateDevices.bind(md);

  md.enumerateDevices = function () {
    return origEnum().then(function (d) {
      if (!d.some(function (x) { return x.kind === 'videoinput'; })) {
        d.push({ deviceId: 'firetv-usb', kind: 'videoinput', label: 'Fire TV USB camera', groupId: 'firetv',
                 toJSON: function () { return this; } });
      }
      return d;
    });
  };

  function nativeStream(constraints) {
    var r = FireCam.start(640, 480);
    if (r.indexOf('error') === 0) {
      return Promise.reject(new DOMException(r, 'NotFoundError'));
    }
    var parts = r.split('x');
    var w = +parts[0], h = +parts[1];
    var cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    var ctx = cv.getContext('2d');
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, w, h);
    var stream = cv.captureStream(30);
    var last = 0, alive = true, busy = false;

    function pump() {
      if (!alive) return;
      if (!busy) {
        var f = FireCam.frame(last);
        if (f) {
          var i = f.indexOf(',');
          last = +f.slice(0, i);
          busy = true;
          fetch('data:image/jpeg;base64,' + f.slice(i + 1))
            .then(function (res) { return res.blob(); })
            .then(function (b) { return createImageBitmap(b); })
            .then(function (bmp) { ctx.drawImage(bmp, 0, 0, w, h); if (bmp.close) bmp.close(); })
            .catch(function () {})
            .then(function () { busy = false; });
        }
      }
      setTimeout(pump, 12);
    }
    pump();

    stream.getVideoTracks().forEach(function (t) {
      var origStop = t.stop.bind(t);
      t.stop = function () { alive = false; try { FireCam.stop(); } catch (e) {} origStop(); };
      var origSettings = t.getSettings.bind(t);
      t.getSettings = function () {
        var s = origSettings(); s.width = w; s.height = h; if (!s.frameRate) s.frameRate = 30;
        s.facingMode = 'user'; return s;
      };
    });

    if (constraints && constraints.audio) {
      return origGUM({ audio: constraints.audio })
        .then(function (a) { a.getAudioTracks().forEach(function (t) { stream.addTrack(t); }); return stream; })
        .catch(function () { return stream; });
    }
    return Promise.resolve(stream);
  }

  md.getUserMedia = function (constraints) {
    if (!constraints || !constraints.video) return origGUM(constraints);
    return origGUM(constraints).catch(function (e) {
      if (e && (e.name === 'NotFoundError' || e.name === 'OverconstrainedError' || e.name === 'NotReadableError')) {
        console.log('[diag] Browser camera not found, using Fire TV USB camera');
        return nativeStream(constraints);
      }
      throw e;
    });
  };
})();
