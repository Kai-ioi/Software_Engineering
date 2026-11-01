package cn.edu.school.submission;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="app.submission.jobs-enabled",havingValue="true",matchIfMissing=true)
public class SubmissionJobs {
    private static final Logger log=LoggerFactory.getLogger(SubmissionJobs.class);
    private final SubmissionService submissions;private final SubmissionNotices notices;
    public SubmissionJobs(SubmissionService submissions,SubmissionNotices notices){this.submissions=submissions;this.notices=notices;}
    @Scheduled(fixedDelayString="${app.submission.freeze-delay-ms:10000}")
    public void freeze(){for(long team:submissions.dueTeams())try{submissions.freezeTeam(team);}catch(RuntimeException e){log.warn("Deadline event needs retry for team {}: {}",team,e.getClass().getSimpleName());}}
    @Scheduled(fixedDelayString="${app.submission.notice-delay-ms:5000}")
    public void notifyRecipients(){for(long id:notices.pending())try{notices.send(id);}catch(RuntimeException e){notices.failed(id);log.warn("Submission notice needs administrative attention after retry exhaustion: event {}",id);}}
}
