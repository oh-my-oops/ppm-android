// Run with bun; fixtures come from the web parser and the installed TanStack version.
import { parseCsv } from "../../src/web/lib/csv-parser.ts";
import { createTable, getCoreRowModel, getSortedRowModel } from "@tanstack/table-core";
const input = ["", "\n", '""', "a,b\n1,2\n", 'a,b\r\n"one,two","a""b"\r\n3', 'a,b\n"multi\nline",z\nlast,,extra', 'a,b\n"unclosed', 'a,b\nx\ry,ok', 'a,b\n"hello"tail,z', 'name,value\nTiếng Việt,🙂\n日,é', 'a,b,\n1,2,', 'a,b\n1,2\n\n'];
let seed=17427;const random=()=>{seed=(seed*1664525+1013904223)>>>0;return seed;};
const chars=['a','b',',','"','\n','\r','é','🙂','0',' '];
for(let n=0;n<100;n++){let s='';for(let i=0,k=random()%100;i<k;i++)s+=chars[random()%chars.length];input.push(s);}
const sorts=[['z','A','a','a2','a10','2','10'],['z','A','a','a2','a10','2','10','B','b','1','a3','0','a01','a1']];
const sorting=sorts.map(values=>{
 const data=values.map(value=>[value]);
 function order(desc){const table=createTable({data,columns:[{id:'value',accessorFn:r=>r[0]}],state:{sorting:[{id:'value',desc}]},getCoreRowModel:getCoreRowModel(),getSortedRowModel:getSortedRowModel()});return table.getRowModel().rows.map(r=>r.index);}
 return {values,asc:order(false),desc:order(true)};
});
await Bun.write(new URL('../core/state/src/test/resources/csv-web-fixtures.json', import.meta.url),JSON.stringify({parser:input.map(content=>({content,...parseCsv(content)})),sorting},null,2)+'\n');
console.log(`${input.length} parser fixtures, ${sorting.length*2} sort fixtures`);
