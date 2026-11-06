const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const assert = require('node:assert/strict')
const source = readFileSync(resolve(__dirname, '../src/stores/workspace.ts'), 'utf8')
const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const auth = { user: { id: 1 } }, data = new Map(), box = { exports: {}, sessionStorage: { getItem: k => data.get(k), setItem: (k,v) => data.set(k,v) }, require: name => name === 'vue' ? { watch() {} } : { auth } }
vm.runInNewContext(output, box)
const { rememberWorkspace: save, readWorkspace: read, scopeTeams } = box.exports
save({ courseId: 1, projectId: 10, teamId: 100 })
auth.user = { id: 2 }; assert.equal(read().courseId, undefined, 'A different user must not inherit the previous account workspace')
auth.user = { id: 1 }; assert.equal(read().teamId, 100)
save({ courseId: 2 }); assert.equal(read().teamId, undefined); assert.equal(read().projectId, undefined)
save({ courseId: 2, projectId: 20, teamId: 200 }); save({ courseId: 2, projectId: 21 }); assert.equal(read().teamId, undefined)
const teams = [{id:1,project_id:10},{id:2,project_id:11}]
assert.deepEqual(scopeTeams(teams, {courseId:'1',projectId:'11'}, 1).map(x=>x.id), [2])
assert.equal(scopeTeams(teams,{courseId:'1',projectId:'99'},1).length,0,'An inaccessible project must not fall back to another project')
assert.equal(scopeTeams(teams,{courseId:'1',projectId:'11'},2).length,2,'Changing course must release the previous course project filter')
data.set('course-workspace-1','invalid json'); assert.equal(read().courseId,undefined)
console.log('Workspace account isolation, course/project resets, scope boundaries and damaged-storage recovery passed.')
