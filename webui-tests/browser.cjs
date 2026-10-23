/* DOM/interaction tests with a fake phone and simulated HTMLMediaElement.
 * These do not verify codecs, real audio, Android services, or Wi-Fi transport.
 * Run: npm install --no-save jsdom@26 && node webui-tests/browser.cjs
 */
const {JSDOM,VirtualConsole}=require('jsdom');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const base=path.join(__dirname,'../app/src/main/assets/webui');
const errors=[],calls=[];
const vc=new VirtualConsole();vc.on('jsdomError',e=>errors.push(e.message));
const dom=new JSDOM(fs.readFileSync(path.join(base,'index.html'),'utf8'),{url:'http://192.168.1.7:8765/',runScripts:'outside-only',pretendToBeVisual:true,virtualConsole:vc});
const w=dom.window;
w.matchMedia=()=>({matches:true,addEventListener(){}});
w.HTMLDialogElement.prototype.showModal=function(){this.open=true};
w.HTMLDialogElement.prototype.close=function(){this.open=false};

const songs=Array.from({length:8},(_,i)=>({key:`s${i}`,type:'song',title:`Song ${i}`,subtitle:'Test artist',image:'',duration:200000}));
const playlist={key:'playlist:example',type:'playlist',title:'Test playlist',subtitle:'Playlist',image:''};
const radio={mode:'balanced',anchors:[songs[0]],key:'radio:s0',type:'radio',title:'Song 0 Radio',subtitle:'USpot Radio',image:''};
const created={...playlist,key:'playlist:created',title:'New test playlist',canRecommend:true};
const liked={...playlist,key:'playlist:liked-songs',title:'Liked Songs'};
const downloads={...playlist,key:'playlist:downloaded-offline',title:'Downloaded'};
let failMutation=false,failCollection=false,failResolve=false,paused=true,currentTime=0,loads=0;
Object.defineProperties(w.HTMLMediaElement.prototype,{paused:{get(){return paused}},duration:{get(){return 200}},readyState:{get(){return 4}},currentTime:{get(){return currentTime},set(v){currentTime=v}}});
w.HTMLMediaElement.prototype.play=function(){paused=false;this.dispatchEvent(new w.Event('playing'));return Promise.resolve()};
w.HTMLMediaElement.prototype.pause=function(){paused=true;this.dispatchEvent(new w.Event('pause'))};
w.HTMLMediaElement.prototype.load=function(){loads++;currentTime=0};
w.fetch=async(url,opts={})=>{
 const req=opts.body?JSON.parse(opts.body):{action:'ping'};calls.push(req);
 let data={ok:true},status=200;
 switch(req.action){
 case 'pin':playlist.pinned=!playlist.pinned;data={pinned:playlist.pinned};break;
 case 'librarySort':data={ok:true};break;
 case 'downloadManager':data={active:[{...songs[0],progress:35}],failed:[{...songs[1],reason:'Needs retry'}],bytes:2097152,completed:3};break;
 case 'home':data={shortcuts:[playlist,liked,radio],sections:[{title:'Made for you',items:[playlist]}]};break;
 case 'library':data={items:[playlist,liked,downloads,radio]};break;
 case 'search':data={songs,albums:[],artists:[],playlists:[playlist],radios:[radio]};break;
 case 'collection':if(failCollection){status=503;data={error:'Temporary test failure'}}else data={item:req.key===radio.key?radio:req.key===liked.key?liked:req.key===created.key?created:playlist,songs:req.key===created.key?[]:songs};break;
 case 'following':data={songs,releases:[playlist],artists:[]};break;
 case 'stats':data={plays:12,artists:2,minutes:6,songs:songs.slice(0,2),activity:[{day:'2026-10-20',plays:3},{day:'2026-10-21',plays:9}]};break;
 case 'radio':data={item:radio,songs};break;
 case 'createPlaylist':data={item:created};break;
 case 'playlistSuggestions':data={songs:songs.slice(0,2)};break;
 case 'addToPlaylist':if(failMutation){status=503;data={error:'Spotify rejected test authorization'}}break;
 case 'removeLibrary':if(failMutation){status=503;data={error:'Spotify rejected test authorization'}}break;
 case 'createRadio':data={item:{...radio,mix:req.mix,anchors:req.songs.map(key=>songs.find(s=>s.key===key))},songs};break;
 case 'tuneRadio':radio.mode=req.mode;data={item:{...radio},songs:[songs[0],...songs.slice(3),...songs.slice(1,3)]};break;
 case 'refreshRadio':data={item:radio,songs};break;
 case 'songState':data={track:songs.find(s=>s.key===req.key)};break;
 case 'like':if(failMutation){status=503;data={error:'Spotify rejected test authorization'}}else{const song=songs.find(s=>s.key===req.key);song.liked=req.saved;data={track:{...song}}}break;
 case 'settings':data={name:'Test account',smartEnabled:false,smartCount:20,smartWifi:true,personalization:true,strength:100};break;
 case 'smartSuggestions':data={songs:[{...songs[0],key:'suggested',title:'Suggested song'}]};break;
 case 'adopt':data={ticket:'adopted-ticket'};break;
 case 'resolve':if(failResolve){status=503;data={error:'Lookup failed in test'};break}data={ticket:`ticket-${req.key}`,url:`/audio/ticket-${req.key}`,track:songs.find(s=>s.key===req.key)};break;
 }
 return {ok:status===200,status,json:async()=>data};
};
w.AbortSignal.timeout=()=>new w.AbortController().signal;
w.eval(fs.readFileSync(path.join(base,'app.js'),'utf8')+'\nwindow.__state=state;window.__enqueue=enqueue;window.__openItem=openItem;window.__collectionMenu=collectionMenu;window.__radioCreator=radioCreator;window.__fullPlayer=fullPlayer;window.__loadTrack=loadTrack;window.__navigate=navigate;window.__search=search;');
const tick=()=>new Promise(resolve=>setTimeout(resolve,15));
const click=q=>{const e=w.document.querySelector(q);assert.ok(e,`Missing ${q}`);e.click()};
const text=id=>w.document.getElementById(id).textContent;
(async()=>{
 await tick();assert.equal(w.document.getElementById('shell').hidden,false);assert.match(text('content'),/Your rotation/);
 // Playback uses the actual selected collection and maintains the next queue item.
 click('.shortcut');await tick();assert.match(text('content'),/8 songs/);click('.collection-actions .primary');await tick();
 assert.equal(text('title'),'Song 0');assert.equal(text('queue-count'),'7 songs');assert.equal(calls.find(c=>c.action==='resolve').collection,playlist.key);
 click('#next');await tick();assert.equal(text('title'),'Song 1');
 click('#previous');await tick();assert.equal(text('title'),'Song 0');
 click('#shuffle');await tick();assert.ok(w.document.getElementById('shuffle').classList.contains('active'));assert.equal(text('title'),'Song 0');
 click('#shuffle');await tick();assert.equal(w.__state.smart,true);click('#shuffle');await tick();assert.equal(w.document.querySelector('.queue-item strong').textContent,'Song 1');
 click('.track-row:nth-child(4) > .quiet:nth-last-child(2)');await tick();assert.equal(w.document.querySelector('.queue-item strong').textContent,'Song 3');assert.equal(text('toast'),'Added to queue');
 // The browser only qualifies a play after 30 seconds of audible time.
 Object.assign(w.__state,{audible:28000,lastTick:w.performance.now()-1000,qualified:false});w.document.getElementById('audio').dispatchEvent(new w.Event('timeupdate'));await tick();
 assert.equal(calls.filter(c=>c.action==='listened').length,0);
 Object.assign(w.__state,{audible:29999,lastTick:w.performance.now()-100,qualified:false});w.document.getElementById('audio').dispatchEvent(new w.Event('timeupdate'));await tick();
 assert.equal(calls.filter(c=>c.action==='listened').length,1);
 // Normal Home navigation always exits the collection.
 click('[data-view="home"]');await tick();assert.match(text('content'),/Your rotation/);
 click('[data-view="following"]');await tick();assert.match(text('content'),/Fresh releases/);
 click('[data-view="stats"]');await tick();assert.match(text('content'),/Qualified plays/);
 click('#theme');assert.equal(w.document.documentElement.dataset.theme,'light');
 // Collection retry stays in that collection rather than opening Library.
 failCollection=true;click('[data-special="liked"]');await tick();assert.match(text('content'),/Temporary test failure/);
 failCollection=false;click('.empty .primary');await tick();assert.match(text('content'),/Liked Songs/);
 assert.match(w.__state.view,/^collection:/);
 // Search results can play, enqueue, and create a real API-backed station.
 w.document.getElementById('search').value='test';w.document.getElementById('search').dispatchEvent(new w.KeyboardEvent('keydown',{key:'Enter'}));await tick();
 assert.match(text('content'),/Results for/);click('.track-name');await tick();assert.equal(text('title'),'Song 0');assert.equal(calls.filter(c=>c.action==='resolve').at(-1).collection,'');
 currentTime=42;const loadsBeforeRadio=loads;click('.track-row button[title="Start USpot Radio"]');await tick();assert.match(text('content'),/USPOT RADIO/);assert.equal(w.__state.collection.type,'radio');
 assert.equal(currentTime,42);assert.equal(loads,loadsBeforeRadio);assert.equal(w.__state.ticket,'adopted-ticket');
 assert.equal(w.document.querySelectorAll('#content .track-row').length,0,'Radio must not render as a playlist');
 assert.match(text('content'),/Stay close/);assert.match(text('content'),/Explore more/);
 const beforeTune=loads;currentTime=55;const originalTicket=w.__state.ticket;
 click('[data-radio-mode="close"]');await tick();assert.equal(currentTime,55);assert.equal(loads,beforeTune);assert.equal(w.__state.ticket,originalTicket);assert.equal(w.__state.collection.mode,'close');
 click('#shuffle');await tick();click('#shuffle');await tick();assert.equal(w.__state.smart,false,'Radio must not enable Smart Shuffle');
 // Manual queue additions remain ordered and survive live radio tuning.
 w.__enqueue(songs[6]);w.__enqueue(songs[5]);assert.equal(w.__state.queue[w.__state.index+1].key,songs[6].key);assert.equal(w.__state.queue[w.__state.index+2].key,songs[5].key);
 click('[data-radio-mode="explore"]');await tick();assert.equal(w.__state.queue[w.__state.index+1].key,songs[6].key);assert.equal(w.__state.queue[w.__state.index+2].key,songs[5].key);
 // Confirmed like state is specific to the selected song; failures do not fake success.
 click('#like-current');await tick();assert.equal(w.__state.current.liked,true);
 failMutation=true;click('#like-current');await tick();assert.equal(w.__state.current.liked,true);assert.match(text('toast'),/Spotify rejected/);failMutation=false;
 click('#current-menu');await tick();assert.match(text('action-dialog'),/Add to playlist/);w.document.getElementById('action-dialog').close();
 click('[data-view="settings"]');await tick();assert.match(text('content'),/Import data/);assert.match(text('content'),/Temporary music & artwork limit: 256 MB/);assert.match(text('content'),/Follow device theme/);assert.equal(w.document.getElementById('pair-form'),null);assert.equal(calls.some(c=>c.token),false);
 // The full player follows the current song and exposes the same controls.
 w.__fullPlayer();assert.equal(text('full-title'),w.__state.current.title);assert.match(text('full-from'),/Radio/);assert.ok(w.document.getElementById('full-like'));w.document.getElementById('action-dialog').close();
 // Playlist creation uses the real action contract; rejected writes do not fake success.
 click('[data-view="library"]');await tick();click('#content .section-heading .circle');await tick();
 w.document.querySelector('#action-dialog input').value='New test playlist';click('#action-dialog .primary');await tick();await tick();assert.ok(calls.some(c=>c.action==='createPlaylist'&&c.name==='New test playlist'));assert.match(text('content'),/Recommended songs/);
 failMutation=true;click('#content .setting-row .transparent-control');await tick();assert.match(text('toast'),/Spotify rejected/);assert.equal(w.document.querySelector('#content .setting-row .transparent-control').textContent,'+ Add');failMutation=false;
 click('#content .setting-row .transparent-control');await tick();assert.equal(w.document.querySelector('#content .setting-row .transparent-control').textContent,'✓ Added');
 failMutation=true;w.__collectionMenu(playlist);[...w.document.querySelectorAll('#action-dialog button')].find(b=>b.textContent==='Remove from your library').click();await tick();assert.match(text('action-dialog'),/will not be deleted/);click('#action-dialog .primary');await tick();assert.match(text('toast'),/Spotify rejected/);assert.equal(calls.filter(c=>c.action==='removeLibrary').at(-1).key,playlist.key);failMutation=false;
 // A mix can select up to five songs together from a Spotify playlist.
 await w.__radioCreator(true);[...w.document.querySelectorAll('.picker-playlists button')].find(b=>b.textContent==='Liked Songs').click();await tick();
 const picks=w.document.querySelectorAll('.picker-track');for(let i=0;i<5;i++){picks[i].click();await tick()}picks[5].click();await tick();assert.match(text('action-dialog'),/5 \/ 5 songs selected/);assert.match(text('toast'),/up to 5/);click('#action-dialog .primary');await tick();await tick();assert.equal(calls.filter(c=>c.action==='createRadio').at(-1).songs.length,5);assert.equal(w.document.querySelectorAll('#content .track-row').length,0);
 // Restore the Radio view before exercising refill/recovery.
 await w.__openItem(radio);
 // Radio can reuse older candidates instead of stalling when a small batch is exhausted.
 w.__state.queue=[...songs];w.__state.index=songs.length-1;w.__state.current=songs.at(-1);w.document.getElementById('audio').dispatchEvent(new w.Event('playing'));await tick();assert.ok(w.__state.queue.length>songs.length);
 // A failed lookup must not leave the previous media URL ready to play under a new title.
 w.__state.nextReady=null;failResolve=true;click('#next');await tick();assert.equal(w.document.getElementById('audio').getAttribute('src'),null);assert.ok(paused);assert.match(text('toast'),/Lookup failed/);
 failResolve=false;click('#play');await tick();assert.match(w.document.getElementById('audio').src,/audio/);assert.equal(paused,false);
 // A rejected background lookup is not reusable as a foreground result.
 const rejected=Promise.reject(new Error('Background lookup expired'));rejected.catch(()=>{});
 const at=w.__state.index;const key=w.__state.queue[at].key;
 w.__state.nextReady={key,promise:rejected};
 const resolvesBefore=calls.filter(c=>c.action==='resolve').length;
 await w.__loadTrack(at);await tick();
 assert.equal(calls.filter(c=>c.action==='resolve').length,resolvesBefore+1);
 assert.equal(calls.filter(c=>c.action==='resolve').at(-1).retry,true);
 assert.equal(paused,false);assert.match(w.document.getElementById('audio').src,/audio/);
 // Code 4 is also reported for HTTP failures; it must permit a fresh lookup.
 Object.defineProperty(w.document.getElementById('audio'),'error',{configurable:true,value:{code:4}});
 w.__state.retries=0;w.document.getElementById('audio').dispatchEvent(new w.Event('error'));
 assert.ok(w.__state.recoveryTimer);assert.match(text('artist'),/retrying in 10 seconds/);
 w.document.getElementById('audio').dispatchEvent(new w.Event('pause'));
 clearTimeout(w.__state.recoveryTimer);
 // Compact utility destinations and the secondary menu remain real navigation.
 await w.__navigate('library');await tick();
 assert.equal(w.document.querySelectorAll('.library-utility').length,2);
 w.document.querySelectorAll('.library-utility')[0].click();await tick();assert.equal(w.__state.view,'history');
 w.document.getElementById('more-views').click();await tick();
 [...w.document.querySelectorAll('#action-dialog button')].find(b=>b.textContent==='Local files').click();await tick();assert.equal(w.__state.view,'local');
 // Preview organization changes reach the shared phone backend.
 await w.__navigate('library');await tick();
 w.__collectionMenu(playlist);[...w.document.querySelectorAll('#action-dialog button')].find(b=>b.textContent==='Pin to library').click();await tick();await tick();
 assert.equal(calls.filter(c=>c.action==='pin').at(-1).key,playlist.key);assert.ok(playlist.pinned);
 assert.equal(w.document.querySelector('.sort-select'),null,'Library sort controls stay hidden');
 await w.__navigate('downloads');await tick();assert.match(text('content'),/2.0 MB/);assert.match(text('content'),/Needs attention/);
 [...w.document.querySelectorAll('#content button')].find(b=>b.textContent==='Retry').click();await tick();assert.equal(calls.filter(c=>c.action==='retryDownload').at(-1).key,songs[1].key);
 [...w.document.querySelectorAll('#content button')].find(b=>b.textContent==='Cancel').click();await tick();assert.equal(calls.filter(c=>c.action==='cancelDownload').at(-1).key,songs[0].key);
 w.document.getElementById('search').value='Song';await w.__search();await tick();assert.match(text('content'),/TOP RESULT/);
 [...w.document.querySelectorAll('#content .chip')].find(b=>b.textContent==='USpot Radios').click();await tick();assert.equal(w.document.querySelectorAll('#content .track-row').length,0);assert.match(text('content'),/Song 0 Radio/);
 await w.__navigate('settings');assert.ok(w.document.querySelectorAll('details.settings-group').length>3);
 assert.match(text('content'),/Test account/);click('.premium-badge');await tick();assert.match(text('action-dialog'),/Unlimited skips/);assert.match(text('action-dialog'),/does not change your Spotify subscription/);[...w.document.querySelectorAll('#action-dialog button')].find(b=>b.textContent==='Keep listening').click();await tick();assert.equal(w.document.getElementById('action-dialog').open,false);
 assert.equal(errors.length,0,errors.join('\n'));console.log('PASS: navigation, collection retry, queue next/previous, smart-shuffle cycle, radio modes without restart, manual FIFO queue, confirmed likes and mutation errors, settings and no-code connection, shuffle restoration, add-next, 30-second history qualification, search playback, radio creation, theme switching, no-restart radio adoption, exhausted-radio refill, and failed-lookup recovery (simulated phone/audio).');
 dom.window.close();
})().catch(e=>{console.error(e);dom.window.close();process.exitCode=1});
