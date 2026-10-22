/* HiveMind, LGPL-3.0. Dependency-free checks for the actual inline terrain mesh. */
'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../src/main/resources/assets/hivemind/network/index.html'),'utf8');
for(const script of html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g))new vm.Script(script[1]);
const source=html.match(/<script id="terrain-mesh">([\s\S]*?)<\/script>/)?.[1];
assert.ok(source,'The same mesh must be packaged in the controller and client dashboard');
const context=vm.createContext({});vm.runInContext(source+'\nglobalThis.mesh=TerrainMesh;',context);
const mesh=context.mesh;
function tile(x,z,y,color=0x73944b){return mesh.prepare({d:'minecraft:overworld',x,z,t:1,rgb:color.toString(16).padStart(6,'0').repeat(256),h:(y&65535).toString(16).padStart(4,'0').repeat(256)});}
const low=tile(0,0,63),high=tile(1,0,79),negative=tile(-1,0,-2),cache=new Map([low,high,negative].map(t=>[mesh.key(t),t]));
for(const scale of [.05,.3,.6,1.2,1.99,2,4]){
    const cells=mesh.cells([low,high,negative],cache,scale),step=mesh.stride(scale);
    assert.equal(cells.length,3*(16/step)**2,'Overview geometry must stay bounded');
    const edge=cells.filter(c=>c.wx+step===16&&c.wz<16);
    assert.ok(edge.length>0);
    for(const c of edge){assert.equal(c.h,64,'Zooming must preserve the surface height');assert.equal(c.east,80,'Cliff walls must join across chunk borders');}
    const signed=cells.filter(c=>c.wx<0);
    assert.ok(signed.every(c=>c.h===-1),'Signed terrain heights and negative chunk positions must survive decoding');
    for(const c of signed.filter(c=>c.wx+step===0))assert.equal(c.east,64,'Neighbor lookup must use floor division west of zero');
    for(let i=1;i<cells.length;i++)assert.ok(cells[i-1].wx+cells[i-1].wz<=cells[i].wx+cells[i].wz,'Painter order must stay consistent across chunks');
}
assert.equal(mesh.cells([{d:low.d,x:5,z:5}],cache,1).length,0,'Unobserved tiles cannot become invented terrain');
const old={d:low.d,x:500,z:-500,c:0x73944b,y:65,t:1};
const overview=mesh.cells([old],new Map(),.05);
assert.equal(overview.length,1,'Historical terrain must remain visible after detail cache eviction or while bots are elsewhere');
assert.equal(overview[0].h,65);assert.equal(overview[0].rgb,old.c);assert.equal(overview[0].wx,8000);
const oldNeighbor={...old,x:501,y:90};
const overviewCliff=mesh.cells([old,oldNeighbor],new Map(),.05);
assert.equal(overviewCliff.find(c=>c.wx===8000).east,90,'Uncached overview tiles must also join cliffs without leaving gaps');
assert.equal(mesh.level(low,16).cells[0].rgb,0x73944b,'Overview color must retain a uniform tile');
assert.throws(()=>mesh.prepare({...low,rgb:'broken'}),/Invalid terrain/);
console.log('Atlas checks passed: stable heights across zoom, chunk-edge cliffs, signed coordinates, bounded detail and script syntax.');

const project=(x,z,h)=>({x:400+(x-z)*4,y:265+((x+z)*.5-(h-64)*.45)*4});
for(const [x,z,h] of [[12,20,160],[-40,-24,-20]]){
    const cell={wx:x,wz:z,h,step:4},p=project(x+2,z+2,h);
    assert.equal(mesh.pick([cell],project,p.x,p.y).h,h,'Picking must use the rendered surface height');
    const dx=(p.x-400)/4,dz=(p.y-265)/4;
    assert.ok(Math.abs(dx*.5+dz+(h-64)*.45-(x+2))<1e-7,'Height-aware inverse projection must recover X');
    assert.ok(Math.abs(-dx*.5+dz+(h-64)*.45-(z+2))<1e-7,'Height-aware inverse projection must recover Z');
}
assert.equal(mesh.pick([],project,400,265),null,'Unmapped terrain must use the explicit approximate fallback');
const back={wx:0,wz:0,h:64,step:16},front={wx:4,wz:4,h:73,step:16},hit=project(8,8,73);
assert.equal(mesh.pick([back,front],project,hit.x,hit.y).h,73,'Overlapping surfaces must pick the frontmost rendered cell');
console.log('Map picking checks passed: elevated terrain, negative coordinates, frontmost surfaces and unmapped fallback.');
// Exercise the production map-order functions against live client roles and dimensions.
(async()=>{
const elements=new Map(),dom=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,hidden:true});return elements.get(id);};
dom('map').getContext=()=>({});dom('dimension').value='minecraft:overworld';dom('projection').value='iso';dom('search').value='';dom('age').value='2592000000';
const sandbox=vm.createContext({document:{getElementById:dom},location:{hash:''},Date,console,AbortSignal,URLSearchParams,setTimeout:()=>{}}),scripts=[...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
vm.runInContext(scripts[0],sandbox);vm.runInContext(scripts[1].split("canvas.addEventListener('wheel'")[0],sandbox);
vm.runInContext("function playerClient(bot){return bot.session?.role==='player';} globalThis.sent=[];async function sendAction(...args){sent.push(args);return true;}",sandbox);
vm.runInContext(scripts[2].split("canvas.addEventListener('contextmenu'")[0],sandbox);
sandbox.fixture=[{id:'bot',dimension:'minecraft:overworld',t:Date.now(),session:{connected:'true'}},{id:'player',dimension:'minecraft:overworld',t:Date.now(),session:{role:'player',connected:'true'}},{id:'nether',dimension:'minecraft:the_nether',t:Date.now()},{id:'offline',dimension:'minecraft:overworld',t:Date.now(),online:false}];
vm.runInContext("control.bots=fixture;mapOrderPoint={dimension:'minecraft:overworld',x:-30,z:40};",sandbox);
assert.deepEqual(Array.from(vm.runInContext('mapEligible(mapOrderPoint).map(b=>b.id)',sandbox)),['bot'],'Map orders must exclude manual, offline and cross-dimension clients');
dom('map-order-target').value='bot';dom('map-order-radius').value='256';await vm.runInContext("mapSend('explore')",sandbox);
assert.equal(sandbox.sent[0][2],'#scout explore minecraft:overworld -30 40 256');assert.equal(sandbox.sent[0][4],'bot','Click orders must target exactly one eligible bot');
vm.runInContext("control.bots[0].dimension='minecraft:the_nether'",sandbox);await vm.runInContext("mapSend('go')",sandbox);assert.equal(sandbox.sent.length,1,'Stale menus must not command a bot that changed dimension');
vm.runInContext("control.bots[0].dimension='minecraft:overworld'",sandbox);dom('map-order-radius').value='9999';await vm.runInContext("mapSend('explore')",sandbox);assert.equal(sandbox.sent.length,1,'Invalid radii must not queue work');
sandbox.testTile={d:'minecraft:overworld',x:-2,z:1,c:0x778844,y:160};vm.runInContext("terrain.tiles=[testTile];markers=[];center={x:0,z:0};scale=4;",sandbox);
const result=vm.runInContext("(()=>{const p=screen(-24,24,160);return mapPick(p.x,p.y);})()",sandbox);assert.equal(result.x,-24);assert.equal(result.z,24);assert.equal(result.mapped,true,'Production picking must recover elevated negative terrain coordinates');
console.log('Map order UI checks passed: exact targets, manual client exclusion, stale dimensions, radius bounds and terrain picking.');
})().catch(error=>{console.error(error);process.exitCode=1;});
