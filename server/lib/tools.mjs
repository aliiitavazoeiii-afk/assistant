export function buildServerTools({ store, nodes, integrations, automations }) {
  const schemas = [
    fn('memory_save','Save durable private memory.',{category:str('Category'),title:str('Short title'),content:str('Full memory'),tags:arrStr('Tags')},['category','title','content','tags']),
    fn('memory_search','Search durable private memory.',{query:str('Search text'),category:str('Exact category or empty'),limit:int('1-100')},['query','category','limit']),
    fn('task_create','Create a private task.',{title:str('Task title'),project:str('Project/category'),dueAt:str('RFC3339 due time or empty'),priority:en(['LOW','NORMAL','HIGH','URGENT'],'Priority')},['title','project','dueAt','priority']),
    fn('task_list','List private tasks.',{status:en(['','OPEN','DONE'],'Status filter'),project:str('Project filter or empty'),limit:int('1-100')},['status','project','limit']),
    fn('task_complete','Mark a private task complete.',{id:str('Task id')},['id']),
    fn('goal_set','Store a measurable goal.',{name:str('Goal name'),target:num('Target'),unit:str('Unit'),deadline:str('Deadline or empty')},['name','target','unit','deadline']),
    fn('goal_list','List goals.',{limit:int('1-100')},['limit']),
    fn('infra_list_nodes','List registered monitoring nodes.',{},[]),
    fn('infra_node_status','Read health from a registered node.',{node_id:str('Node id')},['node_id']),
    fn('infra_service_status','Read status for an allowlisted service.',{node_id:str('Node id'),service:str('Service name')},['node_id','service']),
    fn('infra_service_logs','Read recent logs for an allowlisted service.',{node_id:str('Node id'),service:str('Service name'),lines:int500('1-500')},['node_id','service','lines']),
    fn('vpn_expiring_users','Read VPN users expiring within N days via the node adapter.',{node_id:str('Node id'),days_ahead:int0('0-365')},['node_id','days_ahead']),
    fn('automation_list','List deterministic automations.',{},[]),
    fn('automation_save','Create/update an automation. Disabled unless ASSISTANT_ALLOW_AUTOMATION_CHANGES=true.',{rule_json:str('Complete JSON rule')},['rule_json']),
    fn('automation_run','Run an existing automation now.',{id:str('Automation id')},['id']),
    fn('reports_recent','Read recent automation reports.',{limit:int('1-100')},['limit']),
    fn('audit_recent','Read recent control-plane audit events.',{limit:int('1-100')},['limit']),
    fn('integration_list','List configured named integrations and operations.',{},[]),
    fn('integration_call','Call a preconfigured allowlisted integration operation.',{integration:str('Integration name'),operation:str('Operation name'),params_json:str('JSON object parameters')},['integration','operation','params_json']),
    fn('ops_summary','Get nodes, recent reports, open tasks and goals.',{},[])
  ];
  const names=new Set(schemas.map(x=>x.name));
  async function execute(call){const a=parse(call.arguments);switch(call.name){
    case 'memory_save':return store.addMemory({category:a.category,title:a.title,content:a.content,tags:a.tags||[]}); case 'memory_search':return store.searchMemory(a.query,a.category,a.limit);
    case 'task_create':return store.addTask({title:a.title,project:a.project,dueAt:a.dueAt||null,priority:a.priority}); case 'task_list':return store.listTasks({status:a.status,project:a.project,limit:a.limit}); case 'task_complete':return store.completeTask(a.id);
    case 'goal_set':return store.setGoal({name:a.name,target:a.target,unit:a.unit,deadline:a.deadline||null}); case 'goal_list':return store.listGoals(a.limit);
    case 'infra_list_nodes':return nodes.list(); case 'infra_node_status':return nodes.call(a.node_id,'status',{}); case 'infra_service_status':return nodes.call(a.node_id,'service_status',{service:a.service}); case 'infra_service_logs':return nodes.call(a.node_id,'logs',{service:a.service,lines:a.lines}); case 'vpn_expiring_users':return nodes.call(a.node_id,'vpn_expiring',{daysAhead:a.days_ahead});
    case 'automation_list':return store.listAutomations(); case 'automation_save':if(String(process.env.ASSISTANT_ALLOW_AUTOMATION_CHANGES||'').toLowerCase()!=='true')throw new Error('automation changes are disabled');return store.saveAutomation(parse(a.rule_json)); case 'automation_run':return automations.run(a.id);
    case 'reports_recent':return store.listReports(a.limit); case 'audit_recent':return store.recentAudit(a.limit); case 'integration_list':return integrations.list(); case 'integration_call':return integrations.call(a.integration,a.operation,parse(a.params_json||'{}')); case 'ops_summary':return{nodes:nodes.list(),reports:store.listReports(10),openTasks:store.listTasks({status:'OPEN',limit:20}),goals:store.listGoals(20)}; default:throw new Error(`unknown server tool: ${call.name}`);
  }}
  return{schemas,names,execute};
}
function parse(s){if(typeof s==='object'&&s)return s;try{return JSON.parse(s||'{}');}catch{return{};}}
function fn(name,description,properties,required){return{type:'function',name,description,parameters:{type:'object',properties,required,additionalProperties:false}};} function str(description){return{type:'string',description};} function num(description){return{type:'number',description};} function int(description){return{type:'integer',minimum:1,maximum:100,description};} function int500(description){return{type:'integer',minimum:1,maximum:500,description};} function int0(description){return{type:'integer',minimum:0,maximum:365,description};} function arrStr(description){return{type:'array',items:{type:'string'},description};} function en(values,description){return{type:'string',enum:values,description};}
