package cn.edu.school.submission;

import cn.edu.school.audit.AuditService;
import cn.edu.school.collaboration.ProjectTeamGateway;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.*;
import cn.edu.school.file.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service @Transactional(readOnly=true)
public class SubmissionService {
    private static final long VERSION_MAX=200L*1024*1024;
    private final JdbcTemplate db;private final ProjectTeamGateway gateway;private final CoursePermission permission;
    private final FileService uploads;private final StoredFileRepository files;private final SubmissionFiles storage;
    private final AuditService audit;private final ObjectMapper json;private final Clock clock;
    public SubmissionService(JdbcTemplate db,ProjectTeamGateway gateway,CoursePermission permission,FileService uploads,StoredFileRepository files,SubmissionFiles storage,AuditService audit,ObjectMapper json,Clock clock){
        this.db=db;this.gateway=gateway;this.permission=permission;this.uploads=uploads;this.files=files;this.storage=storage;this.audit=audit;this.json=json;this.clock=clock;
    }
    record Context(long user,Map<String,Object> team,Map<String,Object> project,CourseRole role,boolean member){
        long teamId(){return n(team,"id");}long courseId(){return n(project,"course_id");}
        boolean leader(){return role==CourseRole.STUDENT&&member&&n(team,"leader_id")==user;}
    }
    static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static OffsetDateTime t(Object value){return value instanceof OffsetDateTime date?date:OffsetDateTime.parse(value.toString());}
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    private static void check(boolean ok,String code,String message){if(!ok)throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
    private static void deny(String message){throw new BusinessException(HttpStatus.FORBIDDEN,"SUBMISSION_FORBIDDEN",message);}
    private static void text(String value,int max,String name){check(value!=null&&!value.isBlank()&&value.length()<=max,"INVALID_SUBMISSION",name+"不能为空且不超过"+max+"字");}
    private Context context(long user,long team,boolean lock){
        var tr=gateway.team(team,lock);var p=gateway.project(n(tr,"project_id"));var role=permission.requireMember(user,n(p,"course_id"));
        boolean member=gateway.member(team,user);
        if(role==CourseRole.STUDENT&&!member)deny("只能管理和查看本团队成果");
        return new Context(user,tr,p,role,member);
    }
    private void revision(Context c,long revision){
        if(n(c.team,"revision")!=revision)throw new BusinessException(HttpStatus.CONFLICT,"STALE_WORKSPACE","团队数据已更新，请刷新后重新操作");
    }
    private void lead(Context c){if(!c.leader())deny("正式提交、重交及最终指定仅限本团队负责人");}
    private void student(Context c){if(c.role!=CourseRole.STUDENT||!c.member)deny("待提交材料仅限本团队学生成员准备");}
    private void teacher(Context c){if(c.role!=CourseRole.TEACHER)deny("此操作仅限本课程教师");}
    private void mutableProject(Context c){check(Set.of("PUBLISHED","IN_PROGRESS","ENDED").contains(c.project.get("status").toString()),"PROJECT_READ_ONLY","草稿、终止或归档项目不允许新增成果操作");}
    private Map<String,Object> iteration(Context c,long id){return one("SELECT * FROM team_iteration WHERE id=? AND team_id=?","ITERATION_NOT_FOUND",id,c.teamId());}
    private Map<String,Object> delivery(long team){
        if(db.queryForObject("SELECT COUNT(*) FROM team_delivery WHERE team_id=?",Long.class,team)==0)db.update("INSERT INTO team_delivery(team_id) VALUES(?)",team);
        return db.queryForMap("SELECT * FROM team_delivery WHERE team_id=?",team);
    }
    private void freezeLocked(long team,Map<String,Object> p){
        var d=delivery(team);var deadline=t(p.get("final_deadline"));
        if(d.get("frozen_at")==null&&!now().isBefore(deadline)){
            db.update("UPDATE team_delivery SET frozen_at=?,frozen_deadline=? WHERE team_id=?",now(),deadline,team);
            event(team,null,n(p,"course_id"),"FINAL_FROZEN","team:"+team,"到达项目最终截止，固定已有最终指定；未指定不自动选版",d,delivery(team),true,true);
        }
    }
    @Transactional public void freezeTeam(long team){var tr=gateway.team(team,true);var p=gateway.project(n(tr,"project_id"));if(!p.get("status").equals("DRAFT"))freezeLocked(team,p);}
    public List<Long> dueTeams(){return db.queryForList("SELECT t.id FROM project_team t JOIN course_project p ON p.id=t.project_id LEFT JOIN team_delivery d ON d.team_id=t.id WHERE p.final_deadline<=? AND p.status<>'DRAFT' AND d.frozen_at IS NULL",Long.class,now());}
    private Map<String,Object> activeExtension(Context c,OffsetDateTime accepted){
        var list=db.queryForList("SELECT * FROM submission_extension WHERE team_id=? AND starts_at<=? AND ends_at>? ORDER BY id DESC LIMIT 1",c.teamId(),accepted,accepted);
        return list.isEmpty()?null:list.getFirst();
    }
    private Map<String,Object> window(Context c,OffsetDateTime accepted){
        mutableProject(c);var deadline=t(c.project.get("final_deadline"));
        if(!accepted.isAfter(deadline))return null;
        var extension=activeExtension(c,accepted);check(extension!=null,"SUBMISSION_WINDOW_CLOSED","普通提交已关闭，须经教师批准且位于有效补交窗口内");return extension;
    }
    @Transactional public Map<String,Object> workspace(long user,long team){
        var c=context(user,team,true);freezeLocked(team,c.project);var d=delivery(team);
        var out=new LinkedHashMap<String,Object>();out.put("team",clean(gateway.team(team,false)));out.put("project",clean(c.project));out.put("role",c.role);out.put("isLeader",c.leader());
        boolean mutable=Set.of("PUBLISHED","IN_PROGRESS","ENDED").contains(c.project.get("status").toString());
        boolean ordinary=!now().isAfter(t(c.project.get("final_deadline")));var extension=activeExtension(c,now());
        out.put("canPrepare",mutable&&c.role==CourseRole.STUDENT&&c.member&&(ordinary||extension!=null));
        out.put("canSubmit",mutable&&c.leader()&&(ordinary||extension!=null));
        out.put("canSelect",mutable&&c.leader()&&now().isBefore(t(c.project.get("final_deadline")))&&d.get("frozen_at")==null);
        out.put("delivery",clean(d));out.put("serverTime",now().toString());out.put("iterations",rows("SELECT id,name,status FROM team_iteration WHERE team_id=? ORDER BY sequence_no",team));
        out.put("materials",rows("SELECT m.*,f.size_bytes,f.sha256,u.display_name AS creator_name FROM submission_material m LEFT JOIN stored_file f ON f.id=m.file_id JOIN app_user u ON u.id=m.created_by WHERE m.team_id=? AND m.active=TRUE ORDER BY m.id",team));
        out.put("versions",rows("SELECT v.*,u.display_name AS submitter_name FROM submission_version v JOIN app_user u ON u.id=v.submitted_by WHERE v.team_id=? ORDER BY v.id DESC",team));
        out.put("packages",rows("SELECT * FROM submission_package WHERE team_id=? ORDER BY id",team));
        out.put("extensions",rows("SELECT * FROM submission_extension WHERE team_id=? ORDER BY id DESC",team));
        out.put("requests",rows("SELECT r.*,u.display_name AS requester_name FROM submission_extension_request r JOIN app_user u ON u.id=r.requested_by WHERE r.team_id=? ORDER BY r.id DESC",team));
        out.put("reviewBindings",rows("SELECT * FROM submission_review_binding WHERE team_id=? ORDER BY id DESC",team));
        out.put("notices",rows("SELECT * FROM submission_notice WHERE team_id=? AND user_id=? ORDER BY id DESC LIMIT 30",team,user));
        out.put("exception",!ordinary?(d.get("final_version_id")==null?(db.queryForObject("SELECT COUNT(*) FROM submission_version WHERE team_id=?",Long.class,team)==0?"缺交且未指定最终成果":"未指定最终成果"):""):"");
        return out;
    }
    @Transactional(rollbackFor=Exception.class)
    public long upload(long user,long team,long iteration,long revision,MultipartFile file)throws IOException{
        var c=context(user,team,true);student(c);revision(c,revision);window(c,now());iteration(c,iteration);
        var stored=uploads.upload(user,c.courseId(),file);files.flush();
        long id=insert("INSERT INTO submission_material(team_id,iteration_id,kind,file_id,title,created_by,created_at) VALUES(?,?,'FILE',?,?,?,?)",team,iteration,stored.id(),stored.name(),user,now());
        event(c,"MATERIAL_PREPARED","material:"+id,"准备文件材料",Map.of(),Map.of("id",id,"fileId",stored.id(),"iterationId",iteration),false,false);return id;
    }
    public record LinkInput(long revision,Long iterationId,String title,String url,boolean codeLink,String codeRef){}
    @Transactional public long link(long user,long team,LinkInput in){
        var c=context(user,team,true);student(c);revision(c,in.revision);window(c,now());check(in.iterationId!=null,"MISSING_ITERATION","请选择所属迭代");iteration(c,in.iterationId);
        text(in.title,240,"链接名称");https(in.url);if(in.codeLink)text(in.codeRef,200,"代码提交编号或标签");
        check(in.codeRef==null||in.codeRef.length()<=200,"INVALID_SUBMISSION","版本说明过长");
        long id=insert("INSERT INTO submission_material(team_id,iteration_id,kind,title,url,code_link,code_ref,created_by,created_at) VALUES(?,?,'LINK',?,?,?,?,?,?)",team,in.iterationId,in.title.strip(),in.url.strip(),in.codeLink,in.codeRef,user,now());
        event(c,"MATERIAL_PREPARED","material:"+id,"准备HTTPS链接",Map.of(),Map.of("id",id,"url",in.url),false,false);return id;
    }
    private static void https(String value){
        text(value,2000,"HTTPS链接");
        try{var uri=URI.create(value.strip());check("https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null,"INVALID_LINK","仅接受不含账号密码的有效HTTPS链接");}
        catch(IllegalArgumentException e){throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_LINK","链接格式无效");}
    }
    public record ReasonInput(long revision,String reason){}
    @Transactional public void removeMaterial(long user,long team,long id,ReasonInput in){
        var c=context(user,team,true);student(c);revision(c,in.revision);window(c,now());var old=one("SELECT * FROM submission_material WHERE id=? AND team_id=? AND active=TRUE","MATERIAL_NOT_FOUND",id,team);
        if(!c.leader()&&n(old,"created_by")!=user)deny("普通成员只能移除本人准备的材料");
        db.update("UPDATE submission_material SET active=FALSE WHERE id=?",id);
        event(c,"MATERIAL_REMOVED","material:"+id,"移出待提交区；正式版本文件保留",old,Map.of("active",false),false,false);
    }
    public record SubmitInput(long revision,Long iterationId,List<Long> materialIds,String summary,UUID requestKey){}
    @Transactional public Map<String,Object> submit(long user,long team,SubmitInput in){
        var c=context(user,team,true);lead(c);
        check(in.iterationId!=null&&in.requestKey!=null,"INVALID_SUBMISSION","提交须包含迭代和唯一请求编号");text(in.summary,2000,"成果说明");
        check(in.materialIds!=null&&!in.materialIds.isEmpty(),"INVALID_SUBMISSION","请至少选择一项成果材料");
        check(in.materialIds.stream().allMatch(Objects::nonNull)&&new HashSet<>(in.materialIds).size()==in.materialIds.size(),"INVALID_SUBMISSION","材料不能重复或为空");
        String hash=hash(user+"|"+in.iterationId+"|"+in.summary.strip()+"|"+in.materialIds.stream().sorted().toList());
        var retry=db.queryForList("SELECT * FROM submission_version WHERE team_id=? AND request_key=?",team,in.requestKey);
        if(!retry.isEmpty()){
            if(!retry.getFirst().get("payload_hash").equals(hash))throw new BusinessException(HttpStatus.CONFLICT,"REQUEST_KEY_REUSED","请求编号已用于另一份提交，请核对回执");
            return versionDetail(c,n(retry.getFirst(),"id"));
        }
        revision(c,in.revision);iteration(c,in.iterationId);window(c,now());
        var materials=new ArrayList<Map<String,Object>>();long total=0;
        for(Long id:in.materialIds){
            var m=one("SELECT m.*,f.size_bytes,f.sha256 FROM submission_material m LEFT JOIN stored_file f ON f.id=m.file_id WHERE m.id=? AND m.team_id=? AND m.iteration_id=? AND m.active=TRUE","MATERIAL_NOT_FOUND",id,team,in.iterationId);
            if(m.get("kind").equals("FILE")){long size=n(m,"size_bytes");check(size>0&&size<=50L*1024*1024,"INVALID_FILE","单文件不得超过50 MB");total+=size;}
            else{https(m.get("url").toString());if(Boolean.TRUE.equals(m.get("code_link")))text((String)m.get("code_ref"),200,"代码提交编号或标签");}
            materials.add(m);
        }
        check(total<=VERSION_MAX,"VERSION_TOO_LARGE","每个成果版本的文件总量不得超过200 MB");
        for(var m:materials)if(m.get("file_id")!=null)storage.requireAvailable((UUID)m.get("file_id"),c.courseId());
        // Acceptance is timestamped after permission, material and completeness validation.
        var accepted=now();var extension=window(c,accepted);freezeLocked(team,c.project);
        var packages=db.queryForList("SELECT * FROM submission_package WHERE team_id=? AND iteration_id=?",team,in.iterationId);
        long pkg=packages.isEmpty()?insert("INSERT INTO submission_package(team_id,iteration_id) VALUES(?,?)",team,in.iterationId):n(packages.getFirst(),"id");
        int seq=db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM submission_version WHERE package_id=?",Integer.class,pkg);
        String receipt="T"+team+"-I"+in.iterationId+"-V"+seq+"-"+in.requestKey.toString().substring(0,8);
        long id=insert("INSERT INTO submission_version(package_id,team_id,iteration_id,version_no,submitted_by,summary,accepted_at,deadline_snapshot,late,extension_id,total_bytes,request_key,payload_hash,receipt_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",pkg,team,in.iterationId,seq,user,in.summary.strip(),accepted,t(c.project.get("final_deadline")),accepted.isAfter(t(c.project.get("final_deadline"))),extension==null?null:n(extension,"id"),total,in.requestKey,hash,receipt);
        for(var m:materials)db.update("INSERT INTO submission_version_material(version_id,kind,file_id,title,url,code_link,code_ref,size_bytes,sha256) VALUES(?,?,?,?,?,?,?,?,?)",id,m.get("kind"),m.get("file_id"),m.get("title"),m.get("url"),m.get("code_link"),m.get("code_ref"),m.get("file_id")==null?0:n(m,"size_bytes"),m.get("sha256"));
        db.update("UPDATE submission_package SET current_version_id=? WHERE id=?",id,pkg);
        event(c,"VERSION_SUBMITTED","version:"+id,"负责人正式提交，保留历史及真实受理时间",Map.of(),Map.of("versionId",id,"versionNo",seq,"receipt",receipt,"late",extension!=null),true,true);
        return versionDetail(c,id);
    }
    public record SelectionInput(long revision,Long versionId,String reason,boolean accessibleConfirmed,boolean reassessmentRequired){}
    @Transactional public void select(long user,long team,SelectionInput in){
        var c=context(user,team,true);lead(c);revision(c,in.revision);mutableProject(c);var d=delivery(team);
        check(now().isBefore(t(c.project.get("final_deadline")))&&d.get("frozen_at")==null,"FINAL_FROZEN","最终截止后负责人不能自行更换最终成果");
        check(in.versionId!=null,"MISSING_VERSION","请选择具体已提交版本");version(c,in.versionId);available(c,in.versionId);
        db.update("UPDATE team_delivery SET final_version_id=?,selected_by=?,selected_at=? WHERE team_id=?",in.versionId,user,now(),team);
        event(c,"FINAL_SELECTED","version:"+in.versionId,in.reason==null?"负责人主动指定最终版本":in.reason,d,delivery(team),true,false);
    }
    @Transactional public void verifyFinal(long user,long team,SelectionInput in){
        var c=context(user,team,true);teacher(c);revision(c,in.revision);mutableProject(c);freezeLocked(team,c.project);var d=delivery(team);
        check(!now().isBefore(t(c.project.get("final_deadline"))),"BEFORE_DEADLINE","截止后才处理未指定异常");
        check(d.get("final_version_id")==null,"FINAL_ALREADY_SELECTED","已冻结的最终指定保留；如需改变被评对象，请明确确认新被评版本");
        text(in.reason,2000,"教师核实原因");check(in.versionId!=null&&in.accessibleConfirmed,"CONFIRM_ACCESS_REQUIRED","请选择版本并确认材料可访问");version(c,in.versionId);available(c,in.versionId);
        db.update("UPDATE team_delivery SET final_version_id=?,selected_by=?,selected_at=? WHERE team_id=?",in.versionId,user,now(),team);
        event(c,"FINAL_VERIFIED","version:"+in.versionId,in.reason,d,delivery(team),true,true);
    }
    @Transactional public long bindReview(long user,long team,SelectionInput in){
        var c=context(user,team,true);teacher(c);revision(c,in.revision);mutableProject(c);freezeLocked(team,c.project);var d=delivery(team);
        check(!now().isBefore(t(c.project.get("final_deadline"))),"BEFORE_DEADLINE","最终交付截止后确认被评版本");
        text(in.reason,2000,"确认依据");check(in.versionId!=null&&in.accessibleConfirmed,"CONFIRM_ACCESS_REQUIRED","请选择版本并确认材料可访问");version(c,in.versionId);available(c,in.versionId);
        check(d.get("final_version_id")!=null,"FINAL_NOT_VERIFIED","先核实并解决未指定最终成果的异常");
        if(d.get("review_binding_id")!=null){
            var prior=db.queryForMap("SELECT * FROM submission_review_binding WHERE id=?",d.get("review_binding_id"));
            check(n(prior,"version_id")!=in.versionId||!in.reassessmentRequired,"SAME_REVIEW_VERSION","当前已确认此版本，无需重复确认");
            check(n(prior,"version_id")==in.versionId||in.reassessmentRequired,"REASSESSMENT_REQUIRED","更换已有被评版本须明确记录需要重评，旧评审引用保留");
            if(n(prior,"version_id")==in.versionId)return n(prior,"id");
        }
        long id=insert("INSERT INTO submission_review_binding(team_id,version_id,confirmed_by,confirmed_at,reason,reassessment_required) VALUES(?,?,?,?,?,?)",team,in.versionId,user,now(),in.reason.strip(),in.reassessmentRequired);
        db.update("UPDATE team_delivery SET review_binding_id=? WHERE team_id=?",id,team);
        event(c,"REVIEW_VERSION_CONFIRMED","binding:"+id,in.reason,d,delivery(team),true,true);return id;
    }
    @Transactional public long requestExtension(long user,long team,ReasonInput in){
        var c=context(user,team,true);lead(c);revision(c,in.revision);mutableProject(c);check(now().isAfter(t(c.project.get("final_deadline"))),"BEFORE_DEADLINE","最终截止后可申请限时补交或整改");text(in.reason,2000,"申请原因");
        check(db.queryForObject("SELECT COUNT(*) FROM submission_extension_request WHERE team_id=? AND status='PENDING'",Long.class,team)==0,"REQUEST_PENDING","已有待处理申请，请勿重复申请");
        long id=insert("INSERT INTO submission_extension_request(team_id,requested_by,reason,status,requested_at) VALUES(?,?,?,'PENDING',?)",team,user,in.reason.strip(),now());
        event(c,"EXTENSION_REQUESTED","request:"+id,in.reason,Map.of(),Map.of("requestId",id),false,true);return id;
    }
    public record ExtensionInput(long revision,Long requestId,OffsetDateTime startsAt,OffsetDateTime endsAt,String reason){}
    @Transactional public long grantExtension(long user,long team,ExtensionInput in){
        var c=context(user,team,true);teacher(c);revision(c,in.revision);mutableProject(c);var deadline=t(c.project.get("final_deadline"));
        check(now().isAfter(deadline),"BEFORE_DEADLINE","最终截止后开放团队补交窗口");text(in.reason,2000,"授权原因");
        check(in.startsAt!=null&&in.endsAt!=null&&in.endsAt.isAfter(in.startsAt)&&!in.startsAt.isBefore(deadline)&&in.endsAt.isAfter(now()),"INVALID_WINDOW","窗口须在最终截止之后，结束晚于开始且尚未结束");
        check(Duration.between(in.startsAt,in.endsAt).compareTo(Duration.ofDays(7))<=0,"WINDOW_TOO_LONG","每次授权窗口最长7个自然日");
        if(in.requestId!=null)pending(team,in.requestId);
        long id=insert("INSERT INTO submission_extension(team_id,request_id,starts_at,ends_at,reason,granted_by,granted_at) VALUES(?,?,?,?,?,?,?)",team,in.requestId,in.startsAt,in.endsAt,in.reason.strip(),user,now());
        if(in.requestId!=null)db.update("UPDATE submission_extension_request SET status='APPROVED',resolved_by=?,resolved_at=?,resolution=? WHERE id=?",user,now(),in.reason.strip(),in.requestId);
        event(c,"EXTENSION_GRANTED","extension:"+id,in.reason,Map.of(),Map.of("startsAt",in.startsAt,"endsAt",in.endsAt),true,false);return id;
    }
    @Transactional public void rejectExtension(long user,long team,long id,ReasonInput in){
        var c=context(user,team,true);teacher(c);revision(c,in.revision);mutableProject(c);text(in.reason,2000,"拒绝原因");var old=pending(team,id);
        db.update("UPDATE submission_extension_request SET status='REJECTED',resolved_by=?,resolved_at=?,resolution=? WHERE id=?",user,now(),in.reason.strip(),id);
        event(c,"EXTENSION_REJECTED","request:"+id,in.reason,old,Map.of("status","REJECTED"),true,false);
    }
    private Map<String,Object> pending(long team,long id){var row=one("SELECT * FROM submission_extension_request WHERE id=? AND team_id=?","REQUEST_NOT_FOUND",id,team);check(row.get("status").equals("PENDING"),"REQUEST_RESOLVED","申请已经处理");return row;}
    public Map<String,Object> receipt(long user,long team,UUID key){var c=context(user,team,false);var v=one("SELECT id FROM submission_version WHERE team_id=? AND request_key=?","RECEIPT_NOT_FOUND",team,key);return versionDetail(c,n(v,"id"));}
    public Map<String,Object> detail(long user,long team,long id){return versionDetail(versionContext(user,team,id),id);}
    private Context versionContext(long user,long team,long version){
        var tr=gateway.team(team,false);var p=gateway.project(n(tr,"project_id"));var role=permission.requireMember(user,n(p,"course_id"));boolean member=gateway.member(team,user);
        if(role==CourseRole.STUDENT&&!member){
            long count=db.queryForObject("SELECT COUNT(*) FROM submission_review_access a JOIN submission_review_binding b ON b.id=a.binding_id WHERE b.team_id=? AND b.version_id=? AND a.reviewer_id=? AND a.active=TRUE",Long.class,team,version,user);
            if(count==0)deny("没有访问该成果版本的权限");
        }
        return new Context(user,tr,p,role,member);
    }
    private Map<String,Object> version(Context c,long id){return one("SELECT v.*,u.display_name AS submitter_name FROM submission_version v JOIN app_user u ON u.id=v.submitted_by WHERE v.id=? AND v.team_id=?","VERSION_NOT_FOUND",id,c.teamId());}
    private Map<String,Object> versionDetail(Context c,long id){var out=new LinkedHashMap<>(clean(version(c,id)));out.put("materials",rows("SELECT * FROM submission_version_material WHERE version_id=? ORDER BY id",id));return out;}
    private void available(Context c,long id){for(var f:db.queryForList("SELECT file_id FROM submission_version_material WHERE version_id=? AND file_id IS NOT NULL",id))storage.requireAvailable((UUID)f.get("file_id"),c.courseId());}
    public FileService.Download draftDownload(long user,long team,long material){var c=context(user,team,false);var m=one("SELECT * FROM submission_material WHERE id=? AND team_id=? AND active=TRUE AND kind='FILE'","MATERIAL_NOT_FOUND",material,team);return storage.open((UUID)m.get("file_id"),c.courseId());}
    public FileService.Download versionDownload(long user,long team,long id,long material){var c=versionContext(user,team,id);version(c,id);var m=one("SELECT * FROM submission_version_material WHERE id=? AND version_id=? AND kind='FILE'","MATERIAL_NOT_FOUND",material,id);return storage.open((UUID)m.get("file_id"),c.courseId());}
    public List<Map<String,Object>> history(long user,long team,long before){context(user,team,false);return rows("SELECT e.*,u.display_name AS actor_name FROM submission_event e LEFT JOIN app_user u ON u.id=e.actor_id WHERE e.team_id=? AND e.id<? ORDER BY e.id DESC LIMIT 50",team,before);}
    @Transactional public void readNotice(long user,long team,long id){context(user,team,false);db.update("UPDATE submission_notice SET read_at=? WHERE id=? AND team_id=? AND user_id=?",now(),id,team,user);}
    private void event(Context c,String action,String object,String reason,Map<String,Object> before,Map<String,Object> after,boolean teamNotice,boolean teachers){event(c.teamId(),c.user,c.courseId(),action,object,reason,before,after,teamNotice,teachers);}
    private void event(long team,Long actor,long course,String action,String object,String reason,Map<String,Object> before,Map<String,Object> after,boolean teamNotice,boolean teachers){
        check(reason==null||reason.length()<=2000,"INVALID_SUBMISSION","操作原因不得超过2000字");
        try{db.update("INSERT INTO submission_event(team_id,actor_id,action,object_id,reason,before_data,after_data,occurred_at,notify_team,notify_teachers) VALUES(?,?,?,?,?,?,?,?,?,?)",team,actor,action,object,reason==null?"":reason,json.writeValueAsString(clean(before)),json.writeValueAsString(clean(after)),now(),teamNotice,teachers);}
        catch(JsonProcessingException e){throw new IllegalStateException("无法保存版本过程记录",e);}
        db.update("UPDATE project_team SET revision=revision+1 WHERE id=?",team);
        if(actor!=null)audit.append(actor,course,action,object,"成果管理："+action+"，团队#"+team);
    }
    private Map<String,Object> one(String sql,String code,Object...args){var result=db.queryForList(sql,args);if(result.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,code,"对象不存在或不在授权范围内");return result.getFirst();}
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();db.update(con->{var ps=con.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    static Map<String,Object> clean(Map<String,Object> data){var result=new LinkedHashMap<String,Object>();data.forEach((k,v)->result.put(k,v instanceof OffsetDateTime date?date.toString():v instanceof java.sql.Date date?date.toLocalDate().toString():v));return result;}
    private List<Map<String,Object>> rows(String sql,Object...args){return db.queryForList(sql,args).stream().map(SubmissionService::clean).toList();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
