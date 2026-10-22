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
assert.equal(mesh.level(low,16).cells[0].rgb,0x73944b,'Overview color must retain a uniform tile');
assert.throws(()=>mesh.prepare({...low,rgb:'broken'}),/Invalid terrain/);
console.log('Atlas checks passed: stable heights across zoom, chunk-edge cliffs, signed coordinates, bounded detail and script syntax.');
