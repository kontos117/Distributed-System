package gr.tuc.distributed.manager.service;

import gr.tuc.distributed.common.dto.JobSubmitRequest;
import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.FileMetadata;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.k8s.KubernetesJobLauncher;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import gr.tuc.distributed.manager.repository.JobRepository;
import gr.tuc.distributed.manager.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobOrchestrationServiceTest {

    @Mock JobRepository           jobRepository;
    @Mock TaskRepository          taskRepository;
    @Mock FileMetadataRepository  fileMetadataRepository;
    @Mock KubernetesJobLauncher   k8sLauncher;
    @Mock MinioStorageService     minioService;

    @InjectMocks
    JobOrchestrationService service;

    private final String userId = "user-123";
    private FileMetadata dataFile;
    private FileMetadata codeFile;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultMapTasks", 2);

        dataFile = new FileMetadata();
        dataFile.setFileId(UUID.randomUUID());
        dataFile.setStoragePath("users/u/raw/data.txt");

        codeFile = new FileMetadata();
        codeFile.setFileId(UUID.randomUUID());
        codeFile.setStoragePath("users/u/code/wc.jar");
    }

    // ---- submitJob ----

    @Test
    void submitJob_throwsWhenDataFileNotFound() {
        when(fileMetadataRepository.findByFileIdAndUserId(any(), anyString()))
                .thenReturn(Optional.empty());

        var req = request(dataFile.getFileId(), codeFile.getFileId(), 1);
        assertThatThrownBy(() -> service.submitJob(req, userId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Data file not found");
    }

    @Test
    void submitJob_throwsWhenCodeFileNotFound() {
        when(fileMetadataRepository.findByFileIdAndUserId(eq(dataFile.getFileId()), anyString()))
                .thenReturn(Optional.of(dataFile));
        when(fileMetadataRepository.findByFileIdAndUserId(eq(codeFile.getFileId()), anyString()))
                .thenReturn(Optional.empty());

        var req = request(dataFile.getFileId(), codeFile.getFileId(), 1);
        assertThatThrownBy(() -> service.submitJob(req, userId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Code file not found");
    }

    @Test
    void submitJob_createsJobAndLaunchesMapTasks() {
        stubFileMetadata();
        stubJobSave();
        stubTaskSave();
        when(minioService.listObjects(anyString())).thenReturn(List.of("obj1", "obj2"));
        when(k8sLauncher.launchWorker(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn("mock-pod");

        var req = request(dataFile.getFileId(), codeFile.getFileId(), 2);
        service.submitJob(req, userId);

        verify(k8sLauncher, atLeastOnce())
                .launchWorker(any(), any(), eq("MAP"), anyString(), anyString());
    }

    // ---- handleTaskUpdate ----

    @Test
    void handleTaskUpdate_unknownTaskThrows() {
        when(taskRepository.findById(any())).thenReturn(Optional.empty());

        var update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.COMPLETED);

        assertThatThrownBy(() -> service.handleTaskUpdate(UUID.randomUUID(), update))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void handleTaskUpdate_failedTaskBelowRetryLimitIsRequeued() {
        Task task = buildTask(TaskType.MAP, TaskStatus.IN_PROGRESS, 0);
        Job  job  = task.getJob();

        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(i -> i.getArgument(0));
        when(k8sLauncher.launchWorker(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn("new-pod");

        var update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.FAILED);
        update.setErrorMessage("OOM");

        service.handleTaskUpdate(task.getTaskId(), update);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository, atLeast(2)).save(captor.capture());

        Task saved = captor.getAllValues().stream()
                .filter(t -> t.getRetryCount() == 1)
                .findFirst().orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void handleTaskUpdate_failedTaskAtRetryLimitMarksJobFailed() {
        Task task = buildTask(TaskType.MAP, TaskStatus.IN_PROGRESS, 3);
        Job  job  = task.getJob();

        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.FAILED);
        update.setErrorMessage("permanent");

        service.handleTaskUpdate(task.getTaskId(), update);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    // ---- helpers ----

    private void stubFileMetadata() {
        when(fileMetadataRepository.findByFileIdAndUserId(eq(dataFile.getFileId()), anyString()))
                .thenReturn(Optional.of(dataFile));
        when(fileMetadataRepository.findByFileIdAndUserId(eq(codeFile.getFileId()), anyString()))
                .thenReturn(Optional.of(codeFile));
    }

    private void stubJobSave() {
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            if (j.getJobId() == null) {
                try {
                    var f = Job.class.getDeclaredField("jobId");
                    f.setAccessible(true);
                    f.set(j, UUID.randomUUID());
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            return j;
        });
    }

    private void stubTaskSave() {
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            if (t.getTaskId() == null) {
                try {
                    var f = Task.class.getDeclaredField("taskId");
                    f.setAccessible(true);
                    f.set(t, UUID.randomUUID());
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            return t;
        });
    }

    private JobSubmitRequest request(UUID dataId, UUID codeId, int reducers) {
        var r = new JobSubmitRequest();
        r.setDataId(dataId.toString());
        r.setCodeId(codeId.toString());
        r.setNumReducers(reducers);
        return r;
    }

    private Task buildTask(TaskType type, TaskStatus status, int retryCount) {
        Job job = new Job();
        try {
            var f = Job.class.getDeclaredField("jobId");
            f.setAccessible(true);
            f.set(job, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        job.setUserId(userId);
        job.setStatus(JobStatus.MAP_PHASE);
        job.setCodePath(codeFile.getStoragePath());
        job.setNumReduceTasks(1);

        Task task = new Task();
        try {
            var f = Task.class.getDeclaredField("taskId");
            f.setAccessible(true);
            f.set(task, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        task.setJob(job);
        task.setTaskType(type);
        task.setStatus(status);
        task.setRetryCount(retryCount);
        task.setInputSplit("some/path");
        return task;
    }
}
