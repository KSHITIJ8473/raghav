// CLOCK UNIT
function updateClock() {
    const clockEl = document.getElementById('live-clock');
    const now = new Date();
    clockEl.textContent = now.toLocaleTimeString();
}
setInterval(updateClock, 1000);
updateClock();

// PING STATUS SIMULATOR (Using real CORS-free pings where possible, fallback to random delay)
async function measurePing(url, elementId) {
    const start = performance.now();
    const statusItem = document.getElementById(elementId);
    const indicator = statusItem.querySelector('.status-indicator');
    const pingEl = statusItem.querySelector('.ping-time');

    try {
        // Attempt a fetch with no-cors to calculate actual connection latency
        await fetch(url, { mode: 'no-cors', cache: 'no-store', method: 'HEAD' });
        const end = performance.now();
        const duration = Math.round(end - start);
        
        indicator.className = 'status-indicator online';
        pingEl.textContent = `${duration} ms`;
    } catch (e) {
        // If fetch fails (CORS error still calculates time, but net blocks mean offline)
        // Let's check if the duration was calculated or if it was blocked entirely
        const duration = Math.round(performance.now() - start);
        if (duration < 800) {
            // CORS throws error but connection succeeded
            indicator.className = 'status-indicator online';
            pingEl.textContent = `${duration} ms`;
        } else {
            indicator.className = 'status-indicator offline';
            pingEl.textContent = 'Timeout / Blocked';
        }
    }
}

function updateAllPings() {
    measurePing('https://dami-tv.pro/favicon.ico', 'status-main');
    measurePing('https://chatgpt.hereisman.net/playlist/1/load-playlist', 'status-resolver');
    measurePing('https://gooz.aapmains.net/favicon.ico', 'status-embed');
}
setInterval(updateAllPings, 15000);
updateAllPings();

// UPCOMING FIXTURES DATA & COUNTDOWN
const upcomingFixtures = [
    { id: 1, category: "⚽ UEFA Champions League", teams: "Real Madrid vs Bayern Munich", time: new Date(Date.now() + 2 * 60 * 60 * 1000 + 15 * 60 * 1000) },
    { id: 2, category: "🏀 NBA Playoffs", teams: "Warriors vs Celtics", time: new Date(Date.now() + 4 * 60 * 60 * 1000 + 45 * 60 * 1000) },
    { id: 3, category: "🥊 UFC Fight Night", teams: "Makhachev vs Poirier", time: new Date(Date.now() + 6 * 60 * 60 * 1000) },
    { id: 4, category: "🏎️ Formula 1", teams: "Monaco Grand Prix - Race", time: new Date(Date.now() + 8 * 60 * 60 * 1000 + 30 * 60 * 1000) }
];

function populateFixtures() {
    const container = document.getElementById('fixtures-container');
    container.innerHTML = '';

    upcomingFixtures.forEach(fix => {
        const item = document.createElement('div');
        item.className = 'fixture-item';

        const info = document.createElement('div');
        info.className = 'fixture-match-info';
        info.innerHTML = `
            <span class="fixture-category">${fix.category}</span>
            <span class="fixture-teams">${fix.teams}</span>
        `;

        const timeBox = document.createElement('div');
        timeBox.className = 'fixture-time-box';
        timeBox.innerHTML = `
            <span class="countdown-timer" id="timer-${fix.id}">--:--:--</span>
            <span class="fixture-date">${fix.time.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
        `;

        item.appendChild(info);
        item.appendChild(timeBox);
        container.appendChild(item);
    });
}

function updateCountdowns() {
    upcomingFixtures.forEach(fix => {
        const timerEl = document.getElementById(`timer-${fix.id}`);
        if (!timerEl) return;

        const diff = fix.time - Date.now();
        if (diff <= 0) {
            timerEl.textContent = "LIVE NOW";
            timerEl.style.color = "#e74c3c";
            return;
        }

        const hours = Math.floor(diff / (1000 * 60 * 60));
        const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
        const seconds = Math.floor((diff % (1000 * 60)) / 1000);

        const pad = (num) => String(num).padStart(2, '0');
        timerEl.textContent = `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
    });
}
populateFixtures();
setInterval(updateCountdowns, 1000);
updateCountdowns();

// LIVE SCORES TICKER (Random score changes to simulate real-time updates)
const liveMatches = [
    { category: "⚽ Premier League", teamA: "Chelsea", teamB: "Arsenal", scoreA: 2, scoreB: 1, time: 74, sport: "soccer" },
    { category: "🏀 NBA Finals", teamA: "Lakers", teamB: "Celtics", scoreA: 102, scoreB: 98, time: "Q4 2:14", sport: "basketball" },
    { category: "⚽ La Liga", teamA: "Barcelona", teamB: "Real Madrid", scoreA: 0, scoreB: 0, time: 12, sport: "soccer" }
];

function updateLiveScores() {
    const scoresContainer = document.getElementById('scores-ticker');
    scoresContainer.innerHTML = '';

    // Randomly update match data
    liveMatches.forEach(match => {
        if (Math.random() > 0.85) {
            if (match.sport === "soccer" && match.time < 90) {
                match.time += 1;
                if (Math.random() > 0.90) {
                    if (Math.random() > 0.5) match.scoreA += 1;
                    else match.scoreB += 1;
                }
            } else if (match.sport === "basketball") {
                match.scoreA += Math.floor(Math.random() * 3);
                match.scoreB += Math.floor(Math.random() * 3);
            }
        }

        const scoreCard = document.createElement('div');
        scoreCard.className = 'score-card';
        
        let timeDisplay = match.time;
        if (match.sport === "soccer") {
            timeDisplay = `${match.time}'`;
        }

        scoreCard.innerHTML = `
            <span class="match-category">${match.category}</span>
            <div class="score-row">
                <span class="team-name">${match.teamA}</span>
                <span class="score-numbers font-mono">${match.scoreA} - ${match.scoreB}</span>
                <span class="team-name">${match.teamB}</span>
            </div>
            <span class="match-time animate-pulse"><i class="fa-solid fa-clock-rotate-left"></i> ${timeDisplay}</span>
        `;
        scoresContainer.appendChild(scoreCard);
    });
}
setInterval(updateLiveScores, 3000);
updateLiveScores();

// SIMULATED CHAT ROOM SYSTEM
const chatUsernames = ["GoalHunter", "UltraFan", "StreamKing", "DamiLover", "VpnStreamer", "Cr7Fanboy", "Messi10", "SoccerDiva", "NbaChamp", "F1Speedy"];
const fanComments = [
    "Anyone watching the Chelsea match? Chelsea is dominating!",
    "Dami TV has the best links, zero buffering on Server 1.",
    "Goal!!! What a header by Chelsea!",
    "Where is backup server? Stream 2 working perfectly guys.",
    "UFC starts in 6 hours, can't wait to see Poirier vs Makhachev!",
    "F1 Monaco GP is going to be epic. Hope Leclerc wins his home race.",
    "Is Chromecast working for you guys? Works fine here on Backup stream.",
    "What a strike! Arsenal is pressing hard now.",
    "Celtics are making a comeback in the 4th quarter!",
    "Best plugin by far. Instant domain status check is super helpful."
];

const chatBox = document.getElementById('chat-box');

function addChatMessage(username, message, isSelf = false) {
    const msg = document.createElement('div');
    msg.className = `chat-msg ${isSelf ? 'self' : ''}`;
    
    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    
    msg.innerHTML = `
        <div class="chat-msg-header">
            <span class="chat-username">${username}</span>
            <span class="chat-time-stamp">${time}</span>
        </div>
        <div class="chat-msg-bubble">
            ${message}
        </div>
    `;
    chatBox.appendChild(msg);
    chatBox.scrollTop = chatBox.scrollHeight;
}

// Add some initial messages
setTimeout(() => addChatMessage("DamiLover", "Welcome to Dami TV chatroom! Server status is online!"), 200);
setTimeout(() => addChatMessage("GoalHunter", "Chelsea is playing out of their minds today!"), 1500);
setTimeout(() => addChatMessage("StreamKing", "Yes, Server 1 is running smoothly in 1080p."), 3000);

// Interval to simulate messages
setInterval(() => {
    // Only add message if tab is active (save resources)
    if (document.hidden) return;
    
    const randUser = chatUsernames[Math.floor(Math.random() * chatUsernames.length)];
    const randComment = fanComments[Math.floor(Math.random() * fanComments.length)];
    addChatMessage(randUser, randComment);
}, 6000);

// Online count oscillation
setInterval(() => {
    const onlineEl = document.getElementById('chat-online-count');
    const current = parseInt(onlineEl.textContent.replace(/,/g, ''));
    const change = Math.floor(Math.random() * 21) - 10; // -10 to +10
    onlineEl.textContent = (current + change).toLocaleString();
}, 8000);

// Handling user messages
const chatInput = document.getElementById('chat-input');
const chatSendBtn = document.getElementById('chat-send-btn');

function handleSendMessage() {
    const text = chatInput.value.trim();
    if (!text) return;
    
    addChatMessage("You", text, true);
    chatInput.value = '';

    // Simulated reply after 1-2 seconds
    setTimeout(() => {
        const responses = [
            "Nice!",
            "I agree with that.",
            "Same, honestly.",
            "Really? Let's check Server 2.",
            "Haha true",
            "Let's see what happens!"
        ];
        const randUser = chatUsernames[Math.floor(Math.random() * chatUsernames.length)];
        const randResponse = responses[Math.floor(Math.random() * responses.length)];
        addChatMessage(randUser, randResponse);
    }, 1000 + Math.random() * 1000);
}

chatSendBtn.addEventListener('click', handleSendMessage);
chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        handleSendMessage();
    }
});
