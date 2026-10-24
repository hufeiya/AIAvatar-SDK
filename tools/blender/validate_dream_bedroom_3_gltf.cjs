// Uses the Khronos glTF Validator package. Install it outside the repository,
// then set GLTF_VALIDATOR_MODULE to that package's absolute path.
const fs = require('node:fs');
const path = require('node:path');
const validator = require(process.env.GLTF_VALIDATOR_MODULE || 'gltf-validator');
const folder = path.resolve(__dirname, '../../assets/dream_bedroom_3');
const filename = path.join(folder, 'dream_bedroom_3.glb');
validator.validateBytes(new Uint8Array(fs.readFileSync(filename)), {
  uri: 'dream_bedroom_3.glb', maxIssues: 1000,
}).then(report => {
  fs.writeFileSync(path.join(folder, 'gltf_validation.json'), JSON.stringify(report, null, 2)+'\n');
  console.log(JSON.stringify(report.issues, (key, value) => key === 'messages' ? undefined : value));
  if (report.issues.numErrors || report.issues.numWarnings) process.exitCode = 1;
}).catch(error => { console.error(error); process.exitCode = 1; });
