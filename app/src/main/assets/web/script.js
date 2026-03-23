// Configuration constants
                    const STREAM_RELOAD_DELAY_MS = 200;
                    const CONNECTIONS_REFRESH_DEBOUNCE_MS = 500;
                    const THEME_STORAGE_KEY = 'ipcam-theme-mode';
                    const THEME_MODE_AUTO = 'auto';
                    const THEME_MODE_LIGHT = 'light';
                    const THEME_MODE_DARK = 'dark';
                    const themeMediaQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
                    let currentThemeMode = normalizeThemeMode(document.documentElement.dataset.themeMode);

                    function normalizeThemeMode(themeMode) {
                        if (themeMode === THEME_MODE_LIGHT || themeMode === THEME_MODE_DARK || themeMode === THEME_MODE_AUTO) {
                            return themeMode;
                        }

                        return THEME_MODE_AUTO;
                    }

                    function readStoredThemeMode() {
                        try {
                            return normalizeThemeMode(localStorage.getItem(THEME_STORAGE_KEY));
                        } catch (error) {
                            return THEME_MODE_AUTO;
                        }
                    }

                    function saveThemeMode(themeMode) {
                        try {
                            localStorage.setItem(THEME_STORAGE_KEY, normalizeThemeMode(themeMode));
                        } catch (error) {
                            // Ignore storage access failures; theme still applies for this session.
                        }
                    }

                    function getSystemTheme() {
                        return themeMediaQuery && themeMediaQuery.matches ? THEME_MODE_DARK : THEME_MODE_LIGHT;
                    }

                    function getEffectiveTheme(themeMode) {
                        const normalizedThemeMode = normalizeThemeMode(themeMode);
                        return normalizedThemeMode === THEME_MODE_AUTO ? getSystemTheme() : normalizedThemeMode;
                    }

                    function formatThemeLabel(themeName) {
                        return themeName.charAt(0).toUpperCase() + themeName.slice(1);
                    }

                    function updateThemeControls() {
                        const themeModeSelect = document.getElementById('themeModeSelect');
                        if (themeModeSelect && themeModeSelect.value !== currentThemeMode) {
                            themeModeSelect.value = currentThemeMode;
                        }

                        const themeStatus = document.getElementById('themeStatus');
                        if (!themeStatus) {
                            return;
                        }

                        const effectiveTheme = getEffectiveTheme(currentThemeMode);
                        if (currentThemeMode === THEME_MODE_AUTO) {
                            themeStatus.className = 'alert info theme-status';
                            themeStatus.textContent = 'Theme: Auto. Currently using ' + formatThemeLabel(effectiveTheme) + ' based on your system preference.';
                        } else {
                            themeStatus.className = 'alert info theme-status';
                            themeStatus.textContent = 'Theme is locked to ' + formatThemeLabel(effectiveTheme) + '. Switch back to Auto to follow the system theme.';
                        }
                    }

                    function applyThemeMode(themeMode) {
                        currentThemeMode = normalizeThemeMode(themeMode);
                        document.documentElement.dataset.themeMode = currentThemeMode;
                        document.documentElement.dataset.theme = getEffectiveTheme(currentThemeMode);
                        updateThemeControls();
                    }

                    function initializeThemeControls() {
                        const themeModeSelect = document.getElementById('themeModeSelect');
                        if (themeModeSelect && !themeModeSelect.dataset.initialized) {
                            themeModeSelect.addEventListener('change', function(event) {
                                const nextThemeMode = normalizeThemeMode(event.target.value);
                                saveThemeMode(nextThemeMode);
                                applyThemeMode(nextThemeMode);
                            });
                            themeModeSelect.dataset.initialized = 'true';
                        }

                        updateThemeControls();
                    }

                    function handleSystemThemeChange() {
                        if (currentThemeMode === THEME_MODE_AUTO) {
                            applyThemeMode(THEME_MODE_AUTO);
                        }
                    }

                    if (themeMediaQuery) {
                        if (themeMediaQuery.addEventListener) {
                            themeMediaQuery.addEventListener('change', handleSystemThemeChange);
                        } else if (themeMediaQuery.addListener) {
                            themeMediaQuery.addListener(handleSystemThemeChange);
                        }
                    }

                    applyThemeMode(readStoredThemeMode());
                    
                    // Tab switching functionality
                    function switchTab(tabName) {
                        // Hide all tab contents
                        const contents = document.querySelectorAll('.tab-content');
                        contents.forEach(content => content.classList.remove('active'));
                        
                        // Remove active class from all tabs
                        const tabs = document.querySelectorAll('.tab');
                        tabs.forEach(tab => tab.classList.remove('active'));
                        
                        // Show selected tab content
                        document.getElementById('tab-' + tabName).classList.add('active');
                        
                        // Add active class to clicked tab
                        event.target.classList.add('active');
                    }
                    
                    const streamImg = document.getElementById('stream');
                    const streamPlaceholder = document.getElementById('streamPlaceholder');
                    const toggleStreamBtn = document.getElementById('toggleStreamBtn');
                    let lastFrame = Date.now();
                    let streamActive = false;
                    let autoReloadInterval = null;

                    // Toggle stream on/off with a single button
                    function toggleStream() {
                        if (streamActive) {
                            stopStream();
                        } else {
                            startStream();
                        }
                    }

                    function startStream() {
                        streamImg.src = '/stream?ts=' + Date.now();
                        streamImg.style.display = 'block';
                        streamPlaceholder.style.display = 'none';
                        toggleStreamBtn.textContent = 'Stop Stream';
                        toggleStreamBtn.className = 'danger';
                        streamActive = true;
                        
                        if (autoReloadInterval) clearInterval(autoReloadInterval);
                        autoReloadInterval = setInterval(() => {
                            if (streamActive && Date.now() - lastFrame > 5000) {
                                reloadStream();
                            }
                        }, 3000);
                    }

                    function stopStream() {
                        streamImg.src = '';
                        streamImg.style.display = 'none';
                        streamPlaceholder.style.display = 'block';
                        toggleStreamBtn.textContent = 'Start Stream';
                        toggleStreamBtn.className = 'success';
                        streamActive = false;
                        if (autoReloadInterval) {
                            clearInterval(autoReloadInterval);
                            autoReloadInterval = null;
                        }
                    }

                    function reloadStream() {
                        if (streamActive) {
                            streamImg.src = '/stream?ts=' + Date.now();
                        }
                    }
                    
                    streamImg.onerror = () => {
                        if (streamActive) {
                            setTimeout(reloadStream, 1000);
                        }
                    };
                    streamImg.onload = () => { lastFrame = Date.now(); };

                    function syncCameraSelection(cameraId) {
                        const select = document.getElementById('cameraSelect');
                        if (!select || cameraId === undefined || cameraId === null) {
                            return;
                        }

                        const options = select.options;
                        for (let i = 0; i < options.length; i++) {
                            if (options[i].value === cameraId) {
                                if (select.selectedIndex !== i) {
                                    select.selectedIndex = i;
                                }
                                break;
                            }
                        }
                    }

                    function loadCameras() {
                        return fetch('/cameras')
                            .then(response => response.json())
                            .then(data => {
                                const select = document.getElementById('cameraSelect');
                                select.innerHTML = '';

                                data.cameras.forEach(camera => {
                                    const option = document.createElement('option');
                                    option.value = camera.id;
                                    option.textContent = camera.label;
                                    if (data.selectedCameraId === camera.id) {
                                        option.selected = true;
                                    }
                                    select.appendChild(option);
                                });

                                lastReceivedState.cameraCatalogVersion = data.cameraCatalogVersion;
                                if (data.selectedCameraId) {
                                    lastReceivedState.cameraId = data.selectedCameraId;
                                    lastReceivedState.cameraLabel = data.selectedCameraLabel;
                                    lastReceivedState.cameraFacing = data.selectedCameraFacing;
                                    syncCameraSelection(data.selectedCameraId);
                                }
                            });
                    }

                    function applyCameraSelection() {
                        const select = document.getElementById('cameraSelect');
                        const cameraId = select.value;
                        if (!cameraId) {
                            showAlert('No camera selected', 'warning');
                            return;
                        }

                        const wasStreamActive = streamActive;

                        fetch('/selectCamera?cameraId=' + encodeURIComponent(cameraId))
                            .then(response => response.json())
                            .then(data => {
                                if (data.status !== 'ok') {
                                    throw new Error(data.message || 'Failed to select camera');
                                }

                                lastReceivedState.cameraId = data.cameraId;
                                lastReceivedState.cameraLabel = data.cameraLabel;
                                lastReceivedState.cameraFacing = data.cameraFacing;
                                showAlert('Selected ' + data.cameraLabel, 'success');

                                if (wasStreamActive) {
                                    setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                                }

                                return Promise.all([loadCameras(), loadFormats()]).then(() => {
                                    updateFlashlightButton();
                                });
                            })
                            .catch(error => {
                                showAlert('Error selecting camera: ' + error, 'danger');
                            });
                    }

                    function toggleFlashlight() {
                        fetch('/toggleFlashlight')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    showAlert(data.message, 'success');
                                    updateFlashlightButton();
                                } else {
                                    showAlert(data.message, 'warning');
                                }
                            })
                            .catch(error => {
                                showAlert('Error toggling flashlight: ' + error, 'danger');
                            });
                    }

                    function updateFlashlightButton() {
                        fetch('/status')
                            .then(response => response.json())
                            .then(data => {
                                const button = document.getElementById('flashlightButton');
                                if (data.flashlightAvailable) {
                                    button.disabled = false;
                                    button.textContent = data.flashlightOn ? 'Flashlight: ON' : 'Flashlight: OFF';
                                    button.className = data.flashlightOn ? 'warning' : 'success';
                                } else {
                                    button.disabled = true;
                                    button.textContent = 'Flashlight N/A';
                                    button.className = 'secondary';
                                }
                            })
                            .catch(error => {
                                console.error('Error updating flashlight button:', error);
                            });
                    }
                    
                    function updateBatteryStatusDisplay(batteryMode, streamingAllowed, batteryLevel, isCharging) {
                        const modeText = document.getElementById('batteryModeText');
                        const streamingText = document.getElementById('streamingStatusText');
                        
                        let modeLabel = batteryMode;
                        let modeClass = 'success';
                        
                        if (batteryMode === 'NORMAL') {
                            modeLabel = 'Normal';
                            modeClass = 'success';
                        } else if (batteryMode === 'LOW_BATTERY') {
                            modeLabel = 'Low Battery';
                            modeClass = 'warning';
                        } else if (batteryMode === 'CRITICAL_BATTERY') {
                            modeLabel = 'CRITICAL';
                            modeClass = 'danger';
                        }
                        
                        // Add battery percentage and charging indicator
                        if (batteryLevel !== undefined && batteryLevel !== null) {
                            modeLabel += ' (' + batteryLevel + '%';
                            if (isCharging) {
                                modeLabel += ' ⚡';
                            }
                            modeLabel += ')';
                        }
                        
                        modeText.textContent = modeLabel;
                        modeText.className = 'status-badge ' + modeClass;
                        
                        streamingText.textContent = streamingAllowed ? 'Active' : 'Paused';
                        streamingText.className = streamingAllowed ? 'status-badge success' : 'status-badge danger';
                    }
                    
                    function showAlert(message, type) {
                        const formatStatus = document.getElementById('formatStatus');
                        if (formatStatus) {
                            formatStatus.textContent = message;
                            formatStatus.className = 'alert ' + type;
                            setTimeout(() => {
                                formatStatus.textContent = '';
                                formatStatus.className = 'alert info';
                            }, 5000);
                        }
                    }

                    function loadFormats() {
                        return fetch('/formats')
                            .then(response => response.json())
                            .then(data => {
                                const select = document.getElementById('formatSelect');
                                select.innerHTML = '';
                                const auto = document.createElement('option');
                                auto.value = '';
                                auto.textContent = 'Auto (Camera default)';
                                select.appendChild(auto);
                                data.formats.forEach(fmt => {
                                    const option = document.createElement('option');
                                    option.value = fmt.value;
                                    option.textContent = fmt.label;
                                    if (data.selected === fmt.value) {
                                        option.selected = true;
                                    }
                                    select.appendChild(option);
                                });
                                if (data.selectedCameraId) {
                                    lastReceivedState.cameraId = data.selectedCameraId;
                                    lastReceivedState.cameraLabel = data.selectedCameraLabel;
                                    syncCameraSelection(data.selectedCameraId);
                                }
                                showAlert(data.selected ? 'Selected: ' + data.selected : 'Selected: Auto', 'info');
                            });
                    }

                    function applyFormat() {
                        const value = document.getElementById('formatSelect').value;
                        const url = value ? '/setFormat?value=' + encodeURIComponent(value) : '/setFormat';
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to set format', 'danger');
                            });
                    }

                    function applyCameraOrientation() {
                        const value = document.getElementById('orientationSelect').value;
                        const url = '/setCameraOrientation?value=' + encodeURIComponent(value);
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to set camera orientation', 'danger');
                            });
                    }

                    function applyRotation() {
                        const value = document.getElementById('rotationSelect').value;
                        const url = '/setRotation?value=' + encodeURIComponent(value);
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to set rotation', 'danger');
                            });
                    }

                    function toggleResolutionOverlay() {
                        const checkbox = document.getElementById('resolutionOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setResolutionOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle resolution overlay', 'danger');
                            });
                    }

                    function toggleDateTimeOverlay() {
                        const checkbox = document.getElementById('dateTimeOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setDateTimeOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle date/time overlay', 'danger');
                            });
                    }

                    function toggleBatteryOverlay() {
                        const checkbox = document.getElementById('batteryOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setBatteryOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle battery overlay', 'danger');
                            });
                    }

                    function toggleFpsOverlay() {
                        const checkbox = document.getElementById('fpsOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setFpsOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle FPS overlay', 'danger');
                            });
                    }

                    function applyMjpegFps() {
                        const fps = document.getElementById('mjpegFpsSelect').value;
                        const url = '/setMjpegFps?value=' + fps;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                            })
                            .catch(() => {
                                showAlert('Failed to set MJPEG FPS', 'danger');
                            });
                    }

                    function applyRtspFps() {
                        const fps = document.getElementById('rtspFpsSelect').value;
                        const url = '/setRtspFps?value=' + fps;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                            })
                            .catch(() => {
                                showAlert('Failed to set RTSP FPS', 'danger');
                            });
                    }

                    function refreshConnections() {
                        fetch('/connections')
                            .then(response => {
                                if (!response.ok) {
                                    throw new Error('HTTP ' + response.status);
                                }
                                return response.json();
                            })
                            .then(data => {
                                displayConnections(data.connections);
                            })
                            .catch(error => {
                                console.error('Connection fetch error:', error);
                                document.getElementById('connectionsContainer').innerHTML = 
                                    '<div class="alert danger">Error loading connections. Please refresh the page or check server status.</div>';
                            });
                    }

                    function displayConnections(connections) {
                        const container = document.getElementById('connectionsContainer');
                        
                        if (!connections || connections.length === 0) {
                            container.innerHTML = '<p class="section-description">No active connections</p>';
                            return;
                        }
                        
                        let html = '<table><tr><th>ID</th><th>Kind</th><th>State</th><th>Remote Address</th><th>Endpoint</th><th>Duration (s)</th><th>Action</th></tr>';
                        
                        connections.forEach(conn => {
                            html += '<tr>';
                            html += '<td>' + conn.id + '</td>';
                            html += '<td>' + String(conn.kind || '').toUpperCase() + '</td>';
                            html += '<td>' + conn.state + '</td>';
                            html += '<td>' + conn.remoteAddr + '</td>';
                            html += '<td>' + conn.endpoint + '</td>';
                            html += '<td>' + Math.floor(conn.duration / 1000) + '</td>';
                            html += '<td><button onclick="closeConnection(' + JSON.stringify(conn.id) + ')" class="danger small-button">Close</button></td>';
                            html += '</tr>';
                        });
                        
                        html += '</table>';
                        container.innerHTML = html;
                    }

                    function closeConnection(id) {
                        if (!confirm('Close connection ' + id + '?')) {
                            return;
                        }
                        
                        fetch('/closeConnection?id=' + encodeURIComponent(id))
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                refreshConnections();
                            })
                            .catch(error => {
                                showAlert('Error closing connection: ' + error, 'danger');
                            });
                    }

                    function updateConnectionLimitSelect(selectId, value) {
                        const select = document.getElementById(selectId);
                        if (!select || value === undefined) {
                            return;
                        }

                        const options = select.options;
                        let closestIndex = 0;
                        let closestDistance = Number.POSITIVE_INFINITY;
                        for (let i = 0; i < options.length; i++) {
                            const optionValue = parseInt(options[i].value, 10);
                            const distance = Math.abs(optionValue - value);
                            if (distance < closestDistance) {
                                closestDistance = distance;
                                closestIndex = i;
                            }
                            if (optionValue === value) {
                                if (select.selectedIndex !== i) {
                                    select.selectedIndex = i;
                                }
                                return;
                            }
                        }

                        if (select.selectedIndex !== closestIndex) {
                            select.selectedIndex = closestIndex;
                        }
                    }

                    function applyConnectionLimits() {
                        const mjpegStreams = document.getElementById('maxMjpegStreamsSelect').value;
                        const sseClients = document.getElementById('maxSseClientsSelect').value;
                        const rtspSessions = document.getElementById('maxRtspSessionsSelect').value;
                        const query = new URLSearchParams({
                            mjpegStreams,
                            sseClients,
                            rtspSessions
                        });

                        fetch('/setConnectionLimits?' + query.toString())
                            .then(response => response.json())
                            .then(data => {
                                if (data.connectionLimits) {
                                    updateConnectionLimitSelect('maxMjpegStreamsSelect', data.connectionLimits.maxMjpegStreams);
                                    updateConnectionLimitSelect('maxSseClientsSelect', data.connectionLimits.maxSseClients);
                                    updateConnectionLimitSelect('maxRtspSessionsSelect', data.connectionLimits.maxRtspSessions);
                                }
                                showAlert(data.message, 'success');
                            })
                            .catch(error => {
                                showAlert('Error updating connection limits: ' + error, 'danger');
                            });
                    }

                    function restartServer() {
                        if (!confirm('Restart server? All active connections will be briefly interrupted.')) {
                            return;
                        }
                        
                        showAlert('Restarting server...', 'info');
                        const wasStreamActive = streamActive;
                        
                        fetch('/restart')
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                if (streamActive) {
                                    stopStream();
                                }
                                setTimeout(() => {
                                    showAlert('Server restarted. Reconnecting...', 'info');
                                    if (wasStreamActive) {
                                        startStream();
                                    }
                                }, 3000);
                            })
                            .catch(error => {
                                showAlert('Error restarting server: ' + error, 'danger');
                            });
                    }
                    
                    function resetCamera() {
                        if (!confirm('Reset camera? This will perform a complete camera service reset to recover from frozen/broken states.')) {
                            return;
                        }
                        
                        showAlert('Resetting camera...', 'info');
                        const wasStreamActive = streamActive;
                        
                        fetch('/resetCamera')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    showAlert('Camera reset successful: ' + data.message, 'success');
                                    if (wasStreamActive) {
                                        stopStream();
                                    }
                                    setTimeout(() => {
                                        showAlert('Camera reinitialized. Reconnecting...', 'info');
                                        if (wasStreamActive) {
                                            startStream();
                                        }
                                    }, 2000);
                                } else {
                                    showAlert('Camera reset failed: ' + data.message, 'danger');
                                }
                            })
                            .catch(error => {
                                showAlert('Error resetting camera: ' + error, 'danger');
                            });
                    }
                    
                    function rebootDevice() {
                        if (!confirm('Reboot device? This will completely restart the Android device. Requires Device Owner mode.')) {
                            return;
                        }
                        
                        showAlert('Rebooting device...', 'info');
                        
                        fetch('/reboot')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    showAlert('Device rebooting: ' + data.message, 'success');
                                } else {
                                    showAlert('Reboot failed: ' + data.message, 'danger');
                                }
                            })
                            .catch(error => {
                                // Device might reboot before response completes - this is expected
                                showAlert('Reboot command sent. Device should be restarting...', 'info');
                            });
                    }
                    
                    function showCameraDiagnostics() {
                        showAlert('Loading camera diagnostics...', 'info');
                        
                        fetch('/diagnostics/camera')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    const diag = `Camera Diagnostics:
                                    
State: ${data.cameraState}
Consumers: ${data.consumerCount}
Has Provider: ${data.hasProvider}
Last Frame Size: ${data.lastFrameSizeBytes} bytes
Current FPS: ${data.currentFps.toFixed(1)}
Permission: ${data.permissionGranted ? 'Granted' : 'DENIED'}
MJPEG Clients: ${data.mjpegClients}
RTSP Clients: ${data.rtspClients}
RTSP Enabled: ${data.rtspEnabled}
Server URL: ${data.serverUrl}
Device Name: ${data.deviceName}`;
                                    
                                    alert(diag);
                                } else {
                                    showAlert('Failed to get diagnostics: ' + data.message, 'danger');
                                }
                            })
                            .catch(error => {
                                showAlert('Error fetching diagnostics: ' + error, 'danger');
                            });
                    }
                    
                    function showRebootDiagnostics() {
                        showAlert('Loading reboot diagnostics...', 'info');
                        
                        fetch('/diagnostics/reboot')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    const diag = data.diagnostics;
                                    const diagText = `Reboot Capability Diagnostics:
                                    
Device Owner: ${diag.isDeviceOwner ? 'YES ✓' : 'NO ✗'}
Device Admin: ${diag.isDeviceAdmin ? 'YES' : 'NO'}
Device Locked: ${diag.isDeviceLocked ? 'YES (unlock required)' : 'NO ✓'}
Manufacturer: ${diag.deviceManufacturer}
Model: ${diag.deviceModel}
Android: ${diag.androidVersionName} (API ${diag.androidVersion})
SELinux: ${diag.selinuxStatus}
Knox: ${diag.knoxVersion || 'Not present'}

Reboot Possible: ${diag.rebootPossible ? 'YES ✓' : 'NO ✗'}
${diag.blockingReason ? 'Reason: ' + diag.blockingReason : ''}

${diag.isDeviceOwner ? 'Multiple reboot methods will be tried if primary method fails.' : 'Device Owner mode required for reboot.'}`;
                                    
                                    alert(diagText);
                                } else {
                                    showAlert('Failed to get diagnostics: ' + data.message, 'danger');
                                }
                            })
                            .catch(error => {
                                showAlert('Error fetching diagnostics: ' + error, 'danger');
                            });
                    }

                    function toggleFullscreen() {
                        const container = document.getElementById('streamContainer');
                        
                        if (!document.fullscreenElement && !document.webkitFullscreenElement && 
                            !document.mozFullScreenElement && !document.msFullscreenElement) {
                            if (container.requestFullscreen) {
                                container.requestFullscreen();
                            } else if (container.webkitRequestFullscreen) {
                                container.webkitRequestFullscreen();
                            } else if (container.mozRequestFullScreen) {
                                container.mozRequestFullScreen();
                            } else if (container.msRequestFullscreen) {
                                container.msRequestFullscreen();
                            }
                        } else {
                            if (document.exitFullscreen) {
                                document.exitFullscreen();
                            } else if (document.webkitExitFullscreen) {
                                document.webkitExitFullscreen();
                            } else if (document.mozCancelFullScreen) {
                                document.mozCancelFullScreen();
                            } else if (document.msExitFullscreen) {
                                document.msExitFullscreen();
                            }
                        }
                    }
                    
                    document.addEventListener('fullscreenchange', updateFullscreenButton);
                    document.addEventListener('webkitfullscreenchange', updateFullscreenButton);
                    document.addEventListener('mozfullscreenchange', updateFullscreenButton);
                    document.addEventListener('msfullscreenchange', updateFullscreenButton);
                    
                    function updateFullscreenButton() {
                        const fullscreenBtn = document.getElementById('fullscreenBtn');
                        if (document.fullscreenElement || document.webkitFullscreenElement || 
                            document.mozFullScreenElement || document.msFullscreenElement) {
                            fullscreenBtn.textContent = 'Exit Fullscreen';
                        } else {
                            fullscreenBtn.textContent = 'Fullscreen';
                        }
                    }

                    loadCameras().then(() => loadFormats());
                    refreshConnections();
                    updateFlashlightButton();

                    let lastConnectionCount = '';
                    let lastReceivedState = {};
                    let lastReceivedMetrics = {};
                    
                    // Load connection limits and battery status from server status
                    fetch('/status')
                        .then(response => response.json())
                        .then(data => {
                            if (data.connectionLimits) {
                                updateConnectionLimitSelect('maxMjpegStreamsSelect', data.connectionLimits.maxMjpegStreams);
                                updateConnectionLimitSelect('maxSseClientsSelect', data.connectionLimits.maxSseClients);
                                updateConnectionLimitSelect('maxRtspSessionsSelect', data.connectionLimits.maxRtspSessions);
                            }

                            const connectionCount = document.getElementById('connectionCount');
                            if (connectionCount && data.connectionDisplay) {
                                connectionCount.textContent = data.connectionDisplay;
                                lastConnectionCount = data.connectionDisplay;
                            }

                            if (data.batteryMode && data.streamingAllowed !== undefined) {
                                lastReceivedState.batteryMode = data.batteryMode;
                                lastReceivedState.streamingAllowed = data.streamingAllowed;
                                updateBatteryStatusDisplay(
                                    data.batteryMode,
                                    data.streamingAllowed,
                                    lastReceivedMetrics.batteryLevel,
                                    lastReceivedMetrics.isCharging
                                );
                            }

                            if (data.cameraId !== undefined) {
                                lastReceivedState.cameraId = data.cameraId;
                                lastReceivedState.cameraLabel = data.cameraLabel;
                                lastReceivedState.cameraFacing = data.cameraFacing;
                                syncCameraSelection(data.cameraId);
                            }
                        });

                    function updateConnectionCountDisplay(metrics) {
                        const connectionCount = document.getElementById('connectionCount');
                        if (!connectionCount) {
                            return;
                        }

                        const activeConnections = metrics.totalLongLivedConnections !== undefined
                            ? metrics.totalLongLivedConnections
                            : (metrics.activeHttpStreams || 0) + (metrics.activeSseClients || 0) + (metrics.activeRtspConnections || 0);
                        const displayValue = activeConnections === 1 ? '1 active' : activeConnections + ' active';

                        connectionCount.textContent = displayValue;
                        if (lastConnectionCount !== displayValue) {
                            lastConnectionCount = displayValue;
                            setTimeout(refreshConnections, CONNECTIONS_REFRESH_DEBOUNCE_MS);
                        }
                    }

                    function updateRtspStatusDisplay() {
                        const rtspStatusDisplay = document.getElementById('rtspStatusDisplay');
                        if (!rtspStatusDisplay) {
                            return;
                        }

                        if (lastReceivedState.rtspEnabled) {
                            const rtspFps = Number(lastReceivedMetrics.currentRtspFps || 0).toFixed(1);
                            rtspStatusDisplay.innerHTML = '<span class="status-badge success">Enabled</span><span class="metric-detail">' + rtspFps + ' fps</span>';
                        } else {
                            rtspStatusDisplay.innerHTML = '<span class="status-badge neutral">Disabled</span>';
                        }
                    }

                    function syncRtspIndicator(rtspEnabled, currentRtspFps) {
                        lastReceivedState.rtspEnabled = rtspEnabled;
                        if (currentRtspFps !== undefined) {
                            lastReceivedMetrics.currentRtspFps = currentRtspFps;
                        }
                        if (!rtspEnabled) {
                            lastReceivedMetrics.rtspPlayingSessions = 0;
                        }
                        updateRtspStatusDisplay();
                    }

                    function applyMetrics(metrics) {
                        const cpuUsageDisplay = document.getElementById('cpuUsageDisplay');
                        if (cpuUsageDisplay) {
                            cpuUsageDisplay.textContent = Number(metrics.cpuUsagePercent || 0).toFixed(1);
                        }

                        const bandwidthDisplay = document.getElementById('bandwidthDisplay');
                        if (bandwidthDisplay) {
                            const mbps = (Number(metrics.bandwidthBps || 0) / (1000 * 1000)).toFixed(2);
                            bandwidthDisplay.textContent = mbps;
                        }

                        const cameraFpsDisplay = document.getElementById('currentCameraFpsDisplay');
                        if (cameraFpsDisplay) {
                            cameraFpsDisplay.textContent = Number(metrics.currentCameraFps || 0).toFixed(1);
                        }

                        const mjpegFpsDisplay = document.getElementById('currentMjpegFpsDisplay');
                        if (mjpegFpsDisplay) {
                            mjpegFpsDisplay.textContent = Number(metrics.currentMjpegFps || 0).toFixed(1);
                        }

                        updateRtspStatusDisplay();
                        updateConnectionCountDisplay(metrics);

                        if (lastReceivedState.batteryMode !== undefined && lastReceivedState.streamingAllowed !== undefined) {
                            updateBatteryStatusDisplay(
                                lastReceivedState.batteryMode,
                                lastReceivedState.streamingAllowed,
                                metrics.batteryLevel,
                                metrics.isCharging
                            );
                        }
                    }
                    
                    // Set up Server-Sent Events for real-time updates
                    const eventSource = new EventSource('/events');

                    eventSource.addEventListener('metrics', function(event) {
                        try {
                            const deltaMetrics = JSON.parse(event.data);
                            Object.assign(lastReceivedMetrics, deltaMetrics);
                            applyMetrics(lastReceivedMetrics);
                        } catch (e) {
                            console.error('Failed to handle metrics update:', e);
                        }
                    });
                    
                    eventSource.addEventListener('state', function(event) {
                        try {
                            const deltaState = JSON.parse(event.data);
                            Object.assign(lastReceivedState, deltaState);
                            const state = lastReceivedState;
                            
                            // Update resolution spinner if changed
                            if (deltaState.resolution !== undefined) {
                                const formatSelect = document.getElementById('formatSelect');
                                const options = formatSelect.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (options[i].value === state.resolution || 
                                        (state.resolution === 'auto' && options[i].value === '')) {
                                        if (formatSelect.selectedIndex !== i) {
                                            formatSelect.selectedIndex = i;
                                            console.log('Updated resolution spinner to:', state.resolution);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Update camera orientation spinner if delta contains it
                            if (deltaState.cameraOrientation !== undefined) {
                                const orientationSelect = document.getElementById('orientationSelect');
                                const options = orientationSelect.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (options[i].value === state.cameraOrientation) {
                                        if (orientationSelect.selectedIndex !== i) {
                                            orientationSelect.selectedIndex = i;
                                            console.log('Updated orientation spinner to:', state.cameraOrientation);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Update rotation spinner if delta contains it
                            if (deltaState.rotation !== undefined) {
                                const rotationSelect = document.getElementById('rotationSelect');
                                const options = rotationSelect.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (parseInt(options[i].value) === state.rotation) {
                                        if (rotationSelect.selectedIndex !== i) {
                                            rotationSelect.selectedIndex = i;
                                            console.log('Updated rotation spinner to:', state.rotation);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Update resolution overlay checkbox if delta contains it
                            if (deltaState.showResolutionOverlay !== undefined) {
                                const checkbox = document.getElementById('resolutionOverlayCheckbox');
                                if (checkbox.checked !== state.showResolutionOverlay) {
                                    checkbox.checked = state.showResolutionOverlay;
                                    console.log('Updated resolution overlay checkbox to:', state.showResolutionOverlay);
                                }
                            }
                            
                            // Update OSD overlay checkboxes if delta contains them
                            if (deltaState.showDateTimeOverlay !== undefined) {
                                const checkbox = document.getElementById('dateTimeOverlayCheckbox');
                                if (checkbox && checkbox.checked !== state.showDateTimeOverlay) {
                                    checkbox.checked = state.showDateTimeOverlay;
                                    console.log('Updated date/time overlay checkbox to:', state.showDateTimeOverlay);
                                }
                            }
                            
                            if (deltaState.showBatteryOverlay !== undefined) {
                                const checkbox = document.getElementById('batteryOverlayCheckbox');
                                if (checkbox && checkbox.checked !== state.showBatteryOverlay) {
                                    checkbox.checked = state.showBatteryOverlay;
                                    console.log('Updated battery overlay checkbox to:', state.showBatteryOverlay);
                                }
                            }
                            
                            if (deltaState.showFpsOverlay !== undefined) {
                                const checkbox = document.getElementById('fpsOverlayCheckbox');
                                if (checkbox && checkbox.checked !== state.showFpsOverlay) {
                                    checkbox.checked = state.showFpsOverlay;
                                    console.log('Updated FPS overlay checkbox to:', state.showFpsOverlay);
                                }
                            }
                            
                            if (deltaState.rtspEnabled !== undefined) {
                                updateRtspStatusDisplay();
                            }

                            if (deltaState.maxMjpegStreams !== undefined) {
                                updateConnectionLimitSelect('maxMjpegStreamsSelect', state.maxMjpegStreams);
                            }

                            if (deltaState.maxSseClients !== undefined) {
                                updateConnectionLimitSelect('maxSseClientsSelect', state.maxSseClients);
                            }

                            if (deltaState.maxRtspSessions !== undefined) {
                                updateConnectionLimitSelect('maxRtspSessionsSelect', state.maxRtspSessions);
                            }

                            if (
                                deltaState.maxMjpegStreams !== undefined ||
                                deltaState.maxSseClients !== undefined ||
                                deltaState.maxRtspSessions !== undefined
                            ) {
                                applyMetrics(lastReceivedMetrics);
                            }

                            // Update battery status display
                            if (deltaState.batteryMode !== undefined || deltaState.streamingAllowed !== undefined) {
                                updateBatteryStatusDisplay(
                                    state.batteryMode,
                                    state.streamingAllowed,
                                    lastReceivedMetrics.batteryLevel,
                                    lastReceivedMetrics.isCharging
                                );
                            }
                            
                            if (deltaState.targetMjpegFps !== undefined) {
                                const mjpegSelect = document.getElementById('mjpegFpsSelect');
                                if (mjpegSelect) {
                                    const options = mjpegSelect.options;
                                    for (let i = 0; i < options.length; i++) {
                                        if (parseInt(options[i].value) === state.targetMjpegFps) {
                                            if (mjpegSelect.selectedIndex !== i) {
                                                mjpegSelect.selectedIndex = i;
                                                console.log('Updated MJPEG FPS select to:', state.targetMjpegFps);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            if (deltaState.targetRtspFps !== undefined) {
                                const rtspSelect = document.getElementById('rtspFpsSelect');
                                if (rtspSelect) {
                                    const options = rtspSelect.options;
                                    for (let i = 0; i < options.length; i++) {
                                        if (parseInt(options[i].value) === state.targetRtspFps) {
                                            if (rtspSelect.selectedIndex !== i) {
                                                rtspSelect.selectedIndex = i;
                                                console.log('Updated RTSP FPS select to:', state.targetRtspFps);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            // Update flashlight button state
                            updateFlashlightButton();
                            
                            // Reload stream if it's active and settings changed (not just status)
                            const settingsChanged = deltaState.cameraId !== undefined || deltaState.resolution !== undefined || deltaState.rotation !== undefined;
                            if (streamActive && settingsChanged) {
                                console.log('Reloading stream to reflect state changes');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            }
                            
                            // If streaming was disabled due to battery, stop the stream
                            if (deltaState.streamingAllowed !== undefined && !state.streamingAllowed && streamActive) {
                                console.log('Streaming disabled due to battery, stopping stream');
                                stopStream();
                            }
                            
                            const cameraSelectionChanged =
                                deltaState.cameraId !== undefined ||
                                deltaState.cameraLabel !== undefined ||
                                deltaState.cameraFacing !== undefined ||
                                deltaState.cameraCatalogVersion !== undefined;

                            if (cameraSelectionChanged) {
                                console.log('Camera selection changed, reloading camera metadata');
                                loadCameras();
                                loadFormats();
                            } else if (deltaState.cameraOrientation !== undefined) {
                                console.log('Camera orientation changed, reloading formats');
                                loadFormats();
                            }
                            
                        } catch (e) {
                            console.error('Failed to handle state update:', e);
                        }
                    });
                    
                    eventSource.onerror = function(error) {
                        console.error('SSE connection error:', error);
                        // EventSource will automatically try to reconnect
                    };
                    
                    // Clean up on page unload
                    window.addEventListener('beforeunload', function() {
                        eventSource.close();
                    });
                    
                    // RTSP Control Functions
                    function enableRTSP() {
                        const statusEl = document.getElementById('rtspStatus');
                        statusEl.textContent = 'Enabling RTSP...';
                        statusEl.className = 'alert info';
                        
                        fetch('/enableRTSP')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    syncRtspIndicator(true, 0);
                                    statusEl.className = 'alert success';
                                    statusEl.innerHTML = 
                                        '<strong>✓ RTSP Enabled</strong><br>' +
                                        'Encoder: ' + data.encoder + ' (Hardware: ' + data.isHardware + ')<br>' +
                                        'Color Format: ' + data.colorFormat + ' (' + data.colorFormatHex + ')<br>' +
                                        'URL: <a href="' + data.url + '" target="_blank">' + data.url + '</a><br>' +
                                        'Port: ' + data.port + '<br>' +
                                        'Use with VLC, FFmpeg, ZoneMinder, Shinobi, Blue Iris, MotionEye';
                                } else {
                                    statusEl.className = 'alert danger';
                                    statusEl.innerHTML = '<strong>✗ Failed to enable RTSP</strong><br>' + escapeHtml(data.message) + (data.logsUrl ? ' <a href="' + escapeHtml(data.logsUrl) + '" target="_blank">View logs</a>' : '');
                                }
                            })
                            .catch(error => {
                                statusEl.className = 'alert danger';
                                statusEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    function disableRTSP() {
                        const statusEl = document.getElementById('rtspStatus');
                        statusEl.textContent = 'Disabling RTSP...';
                        statusEl.className = 'alert info';
                        
                        fetch('/disableRTSP')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    syncRtspIndicator(false, 0);
                                    statusEl.className = 'alert warning';
                                    statusEl.innerHTML = '<strong>RTSP Disabled</strong>';
                                } else {
                                    statusEl.className = 'alert danger';
                                    statusEl.innerHTML = '<strong>Error:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                statusEl.className = 'alert danger';
                                statusEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    function checkRTSPStatus() {
                        const statusEl = document.getElementById('rtspStatus');
                        statusEl.textContent = 'Checking RTSP status...';
                        statusEl.className = 'alert info';
                        
                        fetch('/rtspStatus')
                            .then(response => response.json())
                            .then(data => {
                                if (data.rtspEnabled) {
                                    const encodedFps = data.encodedFps > 0 ? data.encodedFps.toFixed(1) : '0.0';
                                    const dropRate = data.framesEncoded > 0 
                                        ? (data.droppedFrames / (data.framesEncoded + data.droppedFrames) * 100).toFixed(1)
                                        : '0.0';
                                    const bandwidthMbps = ((data.currentRtspBandwidthBps || 0) / 1000000).toFixed(2);
                                    
                                    statusEl.className = 'alert success';
                                    statusEl.innerHTML = 
                                        '<strong>✓ RTSP Active</strong><br>' +
                                        'Encoder: ' + data.encoder + ' (Hardware: ' + data.isHardware + ')<br>' +
                                        'Color Format: ' + data.colorFormat + ' (' + data.colorFormatHex + ')<br>' +
                                        'Resolution: ' + data.resolution + ' @ ' + data.bitrateMbps.toFixed(1) + ' Mbps (' + data.bitrateMode + ')<br>' +
                                        'Camera FPS: ' + encodedFps + ' fps (encoder configured: ' + data.targetFps + ' fps)<br>' +
                                        'Frames: ' + data.framesEncoded + ' encoded, ' + data.droppedFrames + ' dropped (' + dropRate + '%)<br>' +
                                        'Bandwidth: ' + bandwidthMbps + ' Mbps (sampled)<br>' +
                                        'Active Sessions: ' + data.activeSessions + ' | Playing: ' + data.playingSessions + '<br>' +
                                        'URL: <a href="' + data.url + '" target="_blank">' + data.url + '</a><br>' +
                                        'Port: ' + data.port;
                                    
                                    // Update encoder settings controls to reflect current values
                                    document.getElementById('bitrateInput').value = data.bitrateMbps.toFixed(1);
                                    document.getElementById('bitrateModeSelect').value = data.bitrateMode;
                                } else {
                                    statusEl.className = 'alert info';
                                    statusEl.innerHTML = 
                                        '<strong>RTSP Not Enabled</strong><br>' +
                                        'Use "Enable RTSP" button to start hardware-accelerated H.264 streaming';
                                }
                            })
                            .catch(error => {
                                statusEl.className = 'alert danger';
                                statusEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    // Check RTSP status on page load
                    window.addEventListener('load', function() {
                        checkRTSPStatus();
                    });
                    
                    function setBitrate() {
                        const bitrate = document.getElementById('bitrateInput').value;
                        const settingsEl = document.getElementById('encoderSettings');
                        settingsEl.textContent = 'Setting bitrate to ' + bitrate + ' Mbps...';
                        settingsEl.className = 'alert info';
                        
                        fetch('/setRTSPBitrate?value=' + bitrate)
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    settingsEl.className = 'alert success';
                                    settingsEl.innerHTML = 
                                        '<strong>✓ Bitrate set to ' + bitrate + ' Mbps</strong><br>' +
                                        'Encoder will restart with new settings. Check status for confirmation.';
                                    setTimeout(checkRTSPStatus, 2000);
                                } else {
                                    settingsEl.className = 'alert danger';
                                    settingsEl.innerHTML = '<strong>✗ Failed:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                settingsEl.className = 'alert danger';
                                settingsEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    function setBitrateMode() {
                        const mode = document.getElementById('bitrateModeSelect').value;
                        const settingsEl = document.getElementById('encoderSettings');
                        settingsEl.textContent = 'Setting bitrate mode to ' + mode + '...';
                        settingsEl.className = 'alert info';
                        
                        fetch('/setRTSPBitrateMode?value=' + mode)
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    settingsEl.className = 'alert success';
                                    settingsEl.innerHTML = 
                                        '<strong>✓ Bitrate mode set to ' + mode + '</strong><br>' +
                                        'Encoder will restart with new settings. Check status for confirmation.';
                                    setTimeout(checkRTSPStatus, 2000);
                                } else {
                                    settingsEl.className = 'alert danger';
                                    settingsEl.innerHTML = '<strong>✗ Failed:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                settingsEl.className = 'alert danger';
                                settingsEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }                    
                    // ==================== Software Update Functions ====================

                    function setStatusPanelState(element, state) {
                        if (!element) {
                            return;
                        }

                        element.className = 'status-panel ' + state;
                    }

                    function buildStatusMessage(state, title, bodyHtml) {
                        let html = '<strong class="status-title ' + state + '">' + title + '</strong>';
                        if (bodyHtml) {
                            html += '<div class="status-details">' + bodyHtml + '</div>';
                        }
                        return html;
                    }
                    
                    /**
                     * Check for available software updates from GitHub Releases
                     */
                    async function checkForUpdate() {
                        const checkBtn = document.getElementById('checkUpdateBtn');
                        const installBtn = document.getElementById('installUpdateBtn');
                        const statusEl = document.getElementById('updateStatus');
                        const statusContainer = document.getElementById('updateStatusContainer');
                        
                        // Disable button and show loading state
                        checkBtn.disabled = true;
                        checkBtn.textContent = 'Checking...';
                        statusEl.innerHTML = '<span class="status-copy">Checking for updates...</span>';
                        setStatusPanelState(statusContainer, 'default');
                        installBtn.style.display = 'none';
                        
                        try {
                            const response = await fetch('/checkUpdate');
                            const result = await response.json();
                            
                            if (result.status === 'ok') {
                                if (result.updateAvailable) {
                                    // Update available
                                    const sizeMB = (result.apkSize / 1024 / 1024).toFixed(2);
                                    setStatusPanelState(statusContainer, 'success');
                                    statusEl.innerHTML =
                                        buildStatusMessage(
                                            'success',
                                            'Update Available',
                                            '<strong>Latest Version:</strong> ' + escapeHtml(String(result.latestVersionName || 'Unknown')) + '<br>' +
                                            '<strong>Current Version:</strong> Build ' + escapeHtml(String(result.currentVersion || 'Unknown')) + '<br>' +
                                            '<strong>Download Size:</strong> ' + sizeMB + ' MB<br>' +
                                            '<strong>Release Notes:</strong>' +
                                            '<div class="release-notes">' + escapeHtml(result.releaseNotes || 'No release notes provided.') + '</div>'
                                        );
                                    installBtn.style.display = 'inline-block';
                                } else {
                                    // No update available
                                    setStatusPanelState(statusContainer, 'info');
                                    statusEl.innerHTML =
                                        buildStatusMessage(
                                            'info',
                                            'You are running the latest version',
                                            '<div class="status-meta">Current version: Build ' + escapeHtml(String(result.currentVersion || 'Unknown')) + '</div>'
                                        );
                                }
                            } else {
                                // Error from server
                                setStatusPanelState(statusContainer, 'danger');
                                statusEl.innerHTML =
                                    buildStatusMessage(
                                        'danger',
                                        'Error',
                                        '<div class="status-meta">' + escapeHtml(String(result.message || 'Unknown error')) + '</div>'
                                    );
                            }
                        } catch (error) {
                            // Network or other error
                            setStatusPanelState(statusContainer, 'danger');
                            statusEl.innerHTML =
                                buildStatusMessage(
                                    'danger',
                                    'Error',
                                    '<div class="status-meta">' + escapeHtml(String(error)) + '</div>'
                                );
                        } finally {
                            // Re-enable button
                            checkBtn.disabled = false;
                            checkBtn.textContent = 'Check for Update';
                        }
                    }
                    
                    /**
                     * Trigger update download and installation
                     */
                    async function triggerUpdate() {
                        const installBtn = document.getElementById('installUpdateBtn');
                        const statusEl = document.getElementById('updateStatus');
                        const statusContainer = document.getElementById('updateStatusContainer');
                        
                        // Confirm with user
                        if (!confirm('This will download and install the update. The app will restart after installation. Continue?')) {
                            return;
                        }
                        
                        // Disable button and show downloading state
                        installBtn.disabled = true;
                        installBtn.textContent = 'Downloading...';
                        setStatusPanelState(statusContainer, 'warning');
                        statusEl.innerHTML =
                            buildStatusMessage(
                                'warning',
                                'Downloading update...',
                                '<div class="status-meta">This may take a minute depending on your connection speed.</div>'
                            );
                        
                        try {
                            const response = await fetch('/triggerUpdate');
                            const result = await response.json();
                            
                            if (result.status === 'ok') {
                                setStatusPanelState(statusContainer, 'success');
                                statusEl.innerHTML =
                                    buildStatusMessage(
                                        'success',
                                        'Download Complete',
                                        '<div class="status-meta">Please confirm installation on your device. The update will be installed and the app will restart.</div>'
                                    );
                                installBtn.style.display = 'none';
                            } else {
                                setStatusPanelState(statusContainer, 'danger');
                                statusEl.innerHTML =
                                    buildStatusMessage(
                                        'danger',
                                        'Error',
                                        '<div class="status-meta">' + escapeHtml(String(result.message || 'Unknown error')) + '</div>'
                                    );
                                installBtn.disabled = false;
                                installBtn.textContent = 'Install Update';
                            }
                        } catch (error) {
                            setStatusPanelState(statusContainer, 'danger');
                            statusEl.innerHTML =
                                buildStatusMessage(
                                    'danger',
                                    'Error',
                                    '<div class="status-meta">' + escapeHtml(String(error)) + '</div>'
                                );
                            installBtn.disabled = false;
                            installBtn.textContent = 'Install Update';
                        }
                    }
                    
                    /**
                     * Helper function to escape HTML to prevent XSS
                     */
                    function escapeHtml(text) {
                        const div = document.createElement('div');
                        div.textContent = text;
                        return div.innerHTML;
                    }
                    
                    // ==================== End Software Update Functions ====================
                    
                    // ==================== ADB Connection Info Display ====================
                    
                    // Show ADB connection info if available on page load
                    document.addEventListener('DOMContentLoaded', function() {
                        initializeThemeControls();
                        const adbInfoElement = document.getElementById('adbConnectionInfo');
                        if (adbInfoElement) {
                            const adbText = adbInfoElement.textContent.trim();
                            if (adbText && adbText !== '') {
                                adbInfoElement.style.display = 'inline';
                            }
                        }
                    });
                    
                    // ==================== End ADB Connection Info ====================
