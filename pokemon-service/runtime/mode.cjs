'use strict';
function parseReadOnly(value) {
  const text=String(value??'false').trim().toLowerCase();
  if(text!=='true'&&text!=='false')throw new Error('POKEMON_READ_ONLY deve ser true ou false.');
  return text==='true';
}
const readOnly=parseReadOnly(process.env.POKEMON_READ_ONLY);
module.exports={readOnly,parseReadOnly};
