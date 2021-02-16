package pro.taskana.task.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pro.taskana.classification.api.ClassificationService;
import pro.taskana.classification.api.exceptions.ClassificationNotFoundException;
import pro.taskana.classification.api.models.Classification;
import pro.taskana.classification.api.models.ClassificationSummary;
import pro.taskana.common.api.BulkOperationResults;
import pro.taskana.common.api.TaskanaRole;
import pro.taskana.common.api.exceptions.ConcurrencyException;
import pro.taskana.common.api.exceptions.InvalidArgumentException;
import pro.taskana.common.api.exceptions.NotAuthorizedException;
import pro.taskana.common.api.exceptions.SystemException;
import pro.taskana.common.api.exceptions.TaskanaException;
import pro.taskana.common.internal.InternalTaskanaEngine;
import pro.taskana.common.internal.util.CheckedConsumer;
import pro.taskana.common.internal.util.CollectionUtil;
import pro.taskana.common.internal.util.IdGenerator;
import pro.taskana.common.internal.util.ObjectAttributeChangeDetector;
import pro.taskana.common.internal.util.Pair;
import pro.taskana.spi.history.api.events.task.TaskCancelledEvent;
import pro.taskana.spi.history.api.events.task.TaskClaimCancelledEvent;
import pro.taskana.spi.history.api.events.task.TaskClaimedEvent;
import pro.taskana.spi.history.api.events.task.TaskCompletedEvent;
import pro.taskana.spi.history.api.events.task.TaskCreatedEvent;
import pro.taskana.spi.history.api.events.task.TaskTerminatedEvent;
import pro.taskana.spi.history.api.events.task.TaskUpdatedEvent;
import pro.taskana.spi.history.internal.HistoryEventManager;
import pro.taskana.spi.task.internal.CreateTaskPreprocessorManager;
import pro.taskana.task.api.CallbackState;
import pro.taskana.task.api.TaskCustomField;
import pro.taskana.task.api.TaskQuery;
import pro.taskana.task.api.TaskService;
import pro.taskana.task.api.TaskState;
import pro.taskana.task.api.exceptions.AttachmentPersistenceException;
import pro.taskana.task.api.exceptions.InvalidOwnerException;
import pro.taskana.task.api.exceptions.InvalidStateException;
import pro.taskana.task.api.exceptions.TaskAlreadyExistException;
import pro.taskana.task.api.exceptions.TaskCommentNotFoundException;
import pro.taskana.task.api.exceptions.TaskNotFoundException;
import pro.taskana.task.api.exceptions.UpdateFailedException;
import pro.taskana.task.api.models.Attachment;
import pro.taskana.task.api.models.ObjectReference;
import pro.taskana.task.api.models.Task;
import pro.taskana.task.api.models.TaskComment;
import pro.taskana.task.api.models.TaskSummary;
import pro.taskana.task.internal.ServiceLevelHandler.BulkLog;
import pro.taskana.task.internal.models.AttachmentImpl;
import pro.taskana.task.internal.models.AttachmentSummaryImpl;
import pro.taskana.task.internal.models.MinimalTaskSummary;
import pro.taskana.task.internal.models.TaskImpl;
import pro.taskana.task.internal.models.TaskSummaryImpl;
import pro.taskana.workbasket.api.WorkbasketPermission;
import pro.taskana.workbasket.api.WorkbasketService;
import pro.taskana.workbasket.api.exceptions.WorkbasketNotFoundException;
import pro.taskana.workbasket.api.models.Workbasket;
import pro.taskana.workbasket.api.models.WorkbasketSummary;
import pro.taskana.workbasket.internal.WorkbasketQueryImpl;
import pro.taskana.workbasket.internal.models.WorkbasketSummaryImpl;

/** This is the implementation of TaskService. */
@SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
public class TaskServiceImpl implements TaskService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskServiceImpl.class);

  private final InternalTaskanaEngine taskanaEngine;
  private final WorkbasketService workbasketService;
  private final ClassificationService classificationService;
  private final TaskMapper taskMapper;
  private final TaskTransferrer taskTransferrer;
  private final TaskCommentServiceImpl taskCommentService;
  private final ServiceLevelHandler serviceLevelHandler;
  private final AttachmentHandler attachmentHandler;
  private final AttachmentMapper attachmentMapper;
  private final HistoryEventManager historyEventManager;
  private final CreateTaskPreprocessorManager createTaskPreprocessorManager;

  public TaskServiceImpl(
      InternalTaskanaEngine taskanaEngine,
      TaskMapper taskMapper,
      TaskCommentMapper taskCommentMapper,
      AttachmentMapper attachmentMapper) {
    this.taskanaEngine = taskanaEngine;
    this.taskMapper = taskMapper;
    this.workbasketService = taskanaEngine.getEngine().getWorkbasketService();
    this.attachmentMapper = attachmentMapper;
    this.classificationService = taskanaEngine.getEngine().getClassificationService();
    this.historyEventManager = taskanaEngine.getHistoryEventManager();
    this.createTaskPreprocessorManager = taskanaEngine.getCreateTaskPreprocessorManager();
    this.taskTransferrer = new TaskTransferrer(taskanaEngine, taskMapper, this);
    this.taskCommentService = new TaskCommentServiceImpl(taskanaEngine, taskCommentMapper, this);
    this.serviceLevelHandler = new ServiceLevelHandler(taskanaEngine, taskMapper, attachmentMapper);
    this.attachmentHandler = new AttachmentHandler(attachmentMapper, classificationService);
  }

  @Override
  public Task claim(String taskId)
      throws TaskNotFoundException, InvalidStateException, InvalidOwnerException,
          NotAuthorizedException {
    return claim(taskId, false);
  }

  @Override
  public Task forceClaim(String taskId)
      throws TaskNotFoundException, InvalidStateException, InvalidOwnerException,
          NotAuthorizedException {
    return claim(taskId, true);
  }

  @Override
  public Task cancelClaim(String taskId)
      throws TaskNotFoundException, InvalidStateException, InvalidOwnerException,
          NotAuthorizedException {
    return this.cancelClaim(taskId, false);
  }

  @Override
  public Task forceCancelClaim(String taskId)
      throws TaskNotFoundException, InvalidStateException, InvalidOwnerException,
          NotAuthorizedException {
    return this.cancelClaim(taskId, true);
  }

  @Override
  public Task completeTask(String taskId)
      throws TaskNotFoundException, InvalidOwnerException, InvalidStateException,
          NotAuthorizedException {
    return completeTask(taskId, false);
  }

  @Override
  public Task forceCompleteTask(String taskId)
      throws TaskNotFoundException, InvalidOwnerException, InvalidStateException,
          NotAuthorizedException {
    return completeTask(taskId, true);
  }

  @Override
  public Task createTask(Task taskToCreate)
      throws NotAuthorizedException, WorkbasketNotFoundException, ClassificationNotFoundException,
          TaskAlreadyExistException, InvalidArgumentException {
    LOGGER.debug("entry to createTask(task = {})", taskToCreate);

    if (CreateTaskPreprocessorManager.isCreateTaskPreprocessorEnabled()) {
      taskToCreate = createTaskPreprocessorManager.processTaskBeforeCreation(taskToCreate);
    }

    TaskImpl task = (TaskImpl) taskToCreate;

    try {
      taskanaEngine.openConnection();

      if (task.getId() != null && !task.getId().equals("")) {
        throw new TaskAlreadyExistException(task.getId());
      }

      LOGGER.debug("Task {} cannot be found, so it can be created.", task.getId());
      Workbasket workbasket;

      if (task.getWorkbasketSummary().getId() != null) {
        workbasket = workbasketService.getWorkbasket(task.getWorkbasketSummary().getId());
      } else if (task.getWorkbasketKey() != null) {
        workbasket = workbasketService.getWorkbasket(task.getWorkbasketKey(), task.getDomain());
      } else {
        String workbasketId = taskanaEngine.getTaskRoutingManager().determineWorkbasketId(task);
        if (workbasketId != null) {
          workbasket = workbasketService.getWorkbasket(workbasketId);
          task.setWorkbasketSummary(workbasket.asSummary());
        } else {
          throw new InvalidArgumentException("Cannot create a task outside a workbasket");
        }
      }

      if (workbasket.isMarkedForDeletion()) {
        throw new WorkbasketNotFoundException(
            workbasket.getId(),
            "The workbasket " + workbasket.getId() + " was marked for deletion");
      }

      task.setWorkbasketSummary(workbasket.asSummary());
      task.setDomain(workbasket.getDomain());

      workbasketService.checkAuthorization(
          task.getWorkbasketSummary().getId(), WorkbasketPermission.APPEND);

      // we do use the key and not the ID to make sure that we use the classification from the right
      // domain.
      // otherwise we would have to check the classification and its domain for validity.
      String classificationKey = task.getClassificationKey();
      if (classificationKey == null || classificationKey.length() == 0) {
        throw new InvalidArgumentException("classificationKey of task must not be empty");
      }

      Classification classification =
          this.classificationService.getClassification(classificationKey, workbasket.getDomain());
      task.setClassificationSummary(classification.asSummary());
      ObjectReference.validate(task.getPrimaryObjRef(), "primary ObjectReference", "Task");
      standardSettings(task, classification);
      setCallbackStateOnTaskCreation(task);
      try {
        this.taskMapper.insert(task);
        LOGGER.debug("Method createTask() created Task '{}'.", task.getId());
        if (HistoryEventManager.isHistoryEnabled()) {

          String details =
              ObjectAttributeChangeDetector.determineChangesInAttributes(newTask(), task);
          historyEventManager.createEvent(
              new TaskCreatedEvent(
                  IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                  task,
                  taskanaEngine.getEngine().getCurrentUserContext().getUserid(),
                  details));
        }
      } catch (PersistenceException e) {
        // Error messages:
        // Postgres: ERROR: duplicate key value violates unique constraint "uc_external_id"
        // DB/2: ### Error updating database.  Cause:
        // com.ibm.db2.jcc.am.SqlIntegrityConstraintViolationException: DB2 SQL Error: SQLCODE=-803,
        // SQLSTATE=23505, SQLERRMC=2;TASKANA.TASK, DRIVER=4.22.29
        //       ### The error may involve pro.taskana.mappings.TaskMapper.insert-Inline
        //       ### The error occurred while setting parameters
        //       ### SQL: INSERT INTO TASK(ID, EXTERNAL_ID, CREATED, CLAIMED, COMPLETED, MODIFIED,
        // PLANNED, DUE, NAME, CREATOR, DESCRIPTION, NOTE, PRIORITY, STATE,
        // CLASSIFICATION_CATEGORY, CLASSIFICATION_KEY, CLASSIFICATION_ID, WORKBASKET_ID,
        // WORKBASKET_KEY, DOMAIN, BUSINESS_PROCESS_ID, PARENT_BUSINESS_PROCESS_ID, OWNER,
        // POR_COMPANY, POR_SYSTEM, POR_INSTANCE, POR_TYPE, POR_VALUE, IS_READ, IS_TRANSFERRED,
        // CALLBACK_INFO, CUSTOM_ATTRIBUTES, CUSTOM_1, CUSTOM_2, CUSTOM_3, CUSTOM_4, CUSTOM_5,
        // CUSTOM_6, CUSTOM_7, CUSTOM_8, CUSTOM_9, CUSTOM_10, CUSTOM_11,  CUSTOM_12,  CUSTOM_13,
        // CUSTOM_14,  CUSTOM_15,  CUSTOM_16 ) VALUES(?,?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        // ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        // ?,  ?)
        //       ### Cause: com.ibm.db2.jcc.am.SqlIntegrityConstraintViolationException: DB2 SQL
        // Error: SQLCODE=-803, SQLSTATE=23505, SQLERRMC=2;TASKANA.TASK, DRIVER=4.22.29
        // H2:   ### Error updating database.  Cause: org.h2.jdbc.JdbcSQLException: Unique index or
        // primary key violation: "UC_EXTERNAL_ID_INDEX_2 ON TASKANA.TASK(EXTERNAL_ID) ...
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : null;
        if (msg != null
            && (msg.contains("violation") || msg.contains("violates") || msg.contains("verletzt"))
            && msg.contains("external_id")) {
          throw new TaskAlreadyExistException(
              "Task with external id " + task.getExternalId() + " already exists");
        } else {
          throw e;
        }
      }
      return task;
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from createTask(task = {})", task);
    }
  }

  @Override
  public Task getTask(String id) throws NotAuthorizedException, TaskNotFoundException {
    LOGGER.debug("entry to getTaskById(id = {})", id);
    TaskImpl resultTask = null;
    try {
      taskanaEngine.openConnection();

      resultTask = taskMapper.findById(id);
      if (resultTask != null) {
        WorkbasketQueryImpl query = (WorkbasketQueryImpl) workbasketService.createWorkbasketQuery();
        query.setUsedToAugmentTasks(true);
        String workbasketId = resultTask.getWorkbasketSummary().getId();
        List<WorkbasketSummary> workbaskets = query.idIn(workbasketId).list();
        if (workbaskets.isEmpty()) {
          String currentUser = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
          throw new NotAuthorizedException(
              "The current user "
                  + currentUser
                  + " has no read permission for workbasket "
                  + workbasketId,
              taskanaEngine.getEngine().getCurrentUserContext().getUserid());
        } else {
          resultTask.setWorkbasketSummary(workbaskets.get(0));
        }

        List<AttachmentImpl> attachmentImpls =
            attachmentMapper.findAttachmentsByTaskId(resultTask.getId());
        if (attachmentImpls == null) {
          attachmentImpls = new ArrayList<>();
        }

        List<ClassificationSummary> classifications;
        classifications = findClassificationForTaskImplAndAttachments(resultTask, attachmentImpls);
        List<Attachment> attachments =
            addClassificationSummariesToAttachments(attachmentImpls, classifications);
        resultTask.setAttachments(attachments);

        String classificationId = resultTask.getClassificationSummary().getId();
        ClassificationSummary classification =
            classifications.stream()
                .filter(c -> c.getId().equals(classificationId))
                .findFirst()
                .orElse(null);
        if (classification == null) {
          throw new SystemException(
              "Could not find a Classification for task " + resultTask.getId());
        }

        resultTask.setClassificationSummary(classification);
        return resultTask;
      } else {
        throw new TaskNotFoundException(id, String.format("Task with id %s was not found.", id));
      }
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from getTaskById(). Returning result {} ", resultTask);
    }
  }

  @Override
  public Task transfer(String taskId, String destinationWorkbasketId, boolean setTransferFlag)
      throws TaskNotFoundException, WorkbasketNotFoundException, NotAuthorizedException,
          InvalidStateException {
    return taskTransferrer.transfer(taskId, destinationWorkbasketId, setTransferFlag);
  }

  @Override
  public Task transfer(String taskId, String workbasketKey, String domain, boolean setTransferFlag)
      throws TaskNotFoundException, WorkbasketNotFoundException, NotAuthorizedException,
          InvalidStateException {
    return taskTransferrer.transfer(taskId, workbasketKey, domain, setTransferFlag);
  }

  @Override
  public Task setTaskRead(String taskId, boolean isRead)
      throws TaskNotFoundException, NotAuthorizedException {
    LOGGER.debug("entry to setTaskRead(taskId = {}, isRead = {})", taskId, isRead);
    TaskImpl task = null;
    try {
      taskanaEngine.openConnection();
      task = (TaskImpl) getTask(taskId);
      task.setRead(isRead);
      task.setModified(Instant.now());
      taskMapper.update(task);
      LOGGER.debug("Method setTaskRead() set read property of Task '{}' to {} ", task, isRead);
      return task;
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from setTaskRead(taskId, isRead). Returning result {} ", task);
    }
  }

  @Override
  public TaskQuery createTaskQuery() {
    return new TaskQueryImpl(taskanaEngine);
  }

  @Override
  public Task newTask() {
    return newTask(null);
  }

  @Override
  public Task newTask(String workbasketId) {
    TaskImpl task = new TaskImpl();
    WorkbasketSummaryImpl wb = new WorkbasketSummaryImpl();
    wb.setId(workbasketId);
    task.setWorkbasketSummary(wb);
    task.setCallbackState(CallbackState.NONE);
    return task;
  }

  @Override
  public Task newTask(String workbasketKey, String domain) {
    LOGGER.debug("entry to newTask(workbasketKey = {}, domain = {})", workbasketKey, domain);
    TaskImpl task = new TaskImpl();
    WorkbasketSummaryImpl wb = new WorkbasketSummaryImpl();
    wb.setKey(workbasketKey);
    wb.setDomain(domain);
    task.setWorkbasketSummary(wb);
    LOGGER.debug("exit from newTask(), returning {}", task);
    return task;
  }

  @Override
  public TaskComment newTaskComment(String taskId) {
    return taskCommentService.newTaskComment(taskId);
  }

  @Override
  public Attachment newAttachment() {
    return new AttachmentImpl();
  }

  @Override
  public Task updateTask(Task task)
      throws InvalidArgumentException, TaskNotFoundException, ConcurrencyException,
          NotAuthorizedException, AttachmentPersistenceException, InvalidStateException,
          ClassificationNotFoundException {
    String userId = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
    LOGGER.debug("entry to updateTask(task = {}, userId = {})", task, userId);
    TaskImpl newTaskImpl = (TaskImpl) task;
    TaskImpl oldTaskImpl;
    try {
      taskanaEngine.openConnection();
      oldTaskImpl = (TaskImpl) getTask(newTaskImpl.getId());

      checkConcurrencyAndSetModified(newTaskImpl, oldTaskImpl);

      attachmentHandler.insertAndDeleteAttachmentsOnTaskUpdate(newTaskImpl, oldTaskImpl);
      ObjectReference.validate(newTaskImpl.getPrimaryObjRef(), "primary ObjectReference", "Task");

      standardUpdateActions(oldTaskImpl, newTaskImpl);

      taskMapper.update(newTaskImpl);

      LOGGER.debug("Method updateTask() updated task '{}' for user '{}'.", task.getId(), userId);

      if (HistoryEventManager.isHistoryEnabled()) {

        String changeDetails =
            ObjectAttributeChangeDetector.determineChangesInAttributes(oldTaskImpl, newTaskImpl);

        historyEventManager.createEvent(
            new TaskUpdatedEvent(
                IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                task,
                taskanaEngine.getEngine().getCurrentUserContext().getUserid(),
                changeDetails));
      }

    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from claim()");
    }
    return task;
  }

  @Override
  public BulkOperationResults<String, TaskanaException> transferTasks(
      String destinationWorkbasketId, List<String> taskIds, boolean setTransferFlag)
      throws NotAuthorizedException, InvalidArgumentException, WorkbasketNotFoundException {
    return taskTransferrer.transferTasks(destinationWorkbasketId, taskIds, setTransferFlag);
  }

  @Override
  public BulkOperationResults<String, TaskanaException> transferTasks(
      String destinationWorkbasketKey,
      String destinationWorkbasketDomain,
      List<String> taskIds,
      boolean setTransferFlag)
      throws NotAuthorizedException, InvalidArgumentException, WorkbasketNotFoundException {
    return taskTransferrer.transferTasks(
        destinationWorkbasketKey, destinationWorkbasketDomain, taskIds, setTransferFlag);
  }

  @Override
  public void deleteTask(String taskId)
      throws TaskNotFoundException, InvalidStateException, NotAuthorizedException {
    deleteTask(taskId, false);
  }

  @Override
  public void forceDeleteTask(String taskId)
      throws TaskNotFoundException, InvalidStateException, NotAuthorizedException {
    deleteTask(taskId, true);
  }

  @Override
  public Task selectAndClaim(TaskQuery taskQuery)
      throws NotAuthorizedException, InvalidOwnerException {

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("entry to selectAndClaim(taskQuery = {})", taskQuery);
    }

    try {

      taskanaEngine.openConnection();

      ((TaskQueryImpl) taskQuery).selectAndClaimEquals(true);

      TaskSummary taskSummary = taskQuery.single();

      if (taskSummary == null) {
        throw new SystemException(
            "No tasks matched the specified filter and sorting options,"
                + " task query returned nothing!");
      }

      return claim(taskSummary.getId());

    } catch (InvalidStateException | TaskNotFoundException e) {
      throw new SystemException("Caught exception ", e);
    } finally {
      LOGGER.debug("exit from selectAndClaim()");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public BulkOperationResults<String, TaskanaException> deleteTasks(List<String> taskIds)
      throws InvalidArgumentException, NotAuthorizedException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("entry to deleteTasks(tasks = {})", taskIds);
    }

    taskanaEngine.getEngine().checkRoleMembership(TaskanaRole.ADMIN);

    try {
      taskanaEngine.openConnection();
      if (taskIds == null) {
        throw new InvalidArgumentException("List of TaskIds must not be null.");
      }
      taskIds = new ArrayList<>(taskIds);

      BulkOperationResults<String, TaskanaException> bulkLog = new BulkOperationResults<>();

      if (taskIds.isEmpty()) {
        return bulkLog;
      }

      List<MinimalTaskSummary> taskSummaries = taskMapper.findExistingTasks(taskIds, null);

      Iterator<String> taskIdIterator = taskIds.iterator();
      while (taskIdIterator.hasNext()) {
        removeSingleTaskForTaskDeletionById(bulkLog, taskSummaries, taskIdIterator);
      }

      if (!taskIds.isEmpty()) {
        attachmentMapper.deleteMultipleByTaskIds(taskIds);
        taskMapper.deleteMultiple(taskIds);

        if (taskanaEngine.getEngine().isHistoryEnabled()
            && taskanaEngine
                .getEngine()
                .getConfiguration()
                .isDeleteHistoryOnTaskDeletionEnabled()) {
          historyEventManager.deleteEvents(taskIds);
        }
      }
      return bulkLog;
    } finally {
      LOGGER.debug("exit from deleteTasks()");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public BulkOperationResults<String, TaskanaException> completeTasks(List<String> taskIds)
      throws InvalidArgumentException {
    return completeTasks(taskIds, false);
  }

  @Override
  public BulkOperationResults<String, TaskanaException> forceCompleteTasks(List<String> taskIds)
      throws InvalidArgumentException {
    return completeTasks(taskIds, true);
  }

  @Override
  public List<String> updateTasks(
      ObjectReference selectionCriteria, Map<TaskCustomField, String> customFieldsToUpdate)
      throws InvalidArgumentException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to updateTasks(selectionCriteria = {}, customFieldsToUpdate = {})",
          selectionCriteria,
          customFieldsToUpdate);
    }
    ObjectReference.validate(selectionCriteria, "ObjectReference", "updateTasks call");
    validateCustomFields(customFieldsToUpdate);
    TaskCustomPropertySelector fieldSelector = new TaskCustomPropertySelector();
    TaskImpl updated = initUpdatedTask(customFieldsToUpdate, fieldSelector);

    try {
      taskanaEngine.openConnection();

      // use query in order to find only those tasks that are visible to the current user
      List<TaskSummary> taskSummaries = getTasksToChange(selectionCriteria);

      List<String> changedTasks = new ArrayList<>();
      if (!taskSummaries.isEmpty()) {
        changedTasks = taskSummaries.stream().map(TaskSummary::getId).collect(Collectors.toList());
        taskMapper.updateTasks(changedTasks, updated, fieldSelector);
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("updateTasks() updated the following tasks: {} ", changedTasks);
        }

      } else {
        LOGGER.debug("updateTasks() found no tasks for update ");
      }
      return changedTasks;
    } finally {
      LOGGER.debug("exit from updateTasks().");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public List<String> updateTasks(
      List<String> taskIds, Map<TaskCustomField, String> customFieldsToUpdate)
      throws InvalidArgumentException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to updateTasks(taskIds = {}, customFieldsToUpdate = {})",
          taskIds,
          customFieldsToUpdate);
    }

    validateCustomFields(customFieldsToUpdate);
    TaskCustomPropertySelector fieldSelector = new TaskCustomPropertySelector();
    TaskImpl updatedTask = initUpdatedTask(customFieldsToUpdate, fieldSelector);

    try {
      taskanaEngine.openConnection();

      // use query in order to find only those tasks that are visible to the current user
      List<TaskSummary> taskSummaries = getTasksToChange(taskIds);

      List<String> changedTasks = new ArrayList<>();
      if (!taskSummaries.isEmpty()) {
        changedTasks = taskSummaries.stream().map(TaskSummary::getId).collect(Collectors.toList());
        taskMapper.updateTasks(changedTasks, updatedTask, fieldSelector);
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("updateTasks() updated the following tasks: {} ", changedTasks);
        }

      } else {
        LOGGER.debug("updateTasks() found no tasks for update ");
      }
      return changedTasks;
    } finally {
      LOGGER.debug("exit from updateTasks().");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public TaskComment createTaskComment(TaskComment taskComment)
      throws NotAuthorizedException, TaskNotFoundException, InvalidArgumentException {
    return taskCommentService.createTaskComment(taskComment);
  }

  @Override
  public TaskComment updateTaskComment(TaskComment taskComment)
      throws NotAuthorizedException, ConcurrencyException, TaskCommentNotFoundException,
          TaskNotFoundException, InvalidArgumentException {
    return taskCommentService.updateTaskComment(taskComment);
  }

  @Override
  public void deleteTaskComment(String taskCommentId)
      throws NotAuthorizedException, TaskCommentNotFoundException, TaskNotFoundException,
          InvalidArgumentException {
    taskCommentService.deleteTaskComment(taskCommentId);
  }

  @Override
  public TaskComment getTaskComment(String taskCommentid)
      throws TaskCommentNotFoundException, NotAuthorizedException, TaskNotFoundException,
          InvalidArgumentException {
    return taskCommentService.getTaskComment(taskCommentid);
  }

  @Override
  public List<TaskComment> getTaskComments(String taskId)
      throws NotAuthorizedException, TaskNotFoundException {

    return taskCommentService.getTaskComments(taskId);
  }

  @Override
  public BulkOperationResults<String, TaskanaException> setCallbackStateForTasks(
      List<String> externalIds, CallbackState state) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("entry to setCallbackStateForTasks(externalIds = {})", externalIds);
    }
    try {
      taskanaEngine.openConnection();

      BulkOperationResults<String, TaskanaException> bulkLog = new BulkOperationResults<>();

      if (externalIds == null || externalIds.isEmpty()) {
        return bulkLog;
      }

      List<MinimalTaskSummary> taskSummaries = taskMapper.findExistingTasks(null, externalIds);

      Iterator<String> taskIdIterator = new ArrayList<>(externalIds).iterator();
      while (taskIdIterator.hasNext()) {
        removeSingleTaskForCallbackStateByExternalId(bulkLog, taskSummaries, taskIdIterator, state);
      }
      if (!externalIds.isEmpty()) {
        taskMapper.setCallbackStateMultiple(externalIds, state);
      }
      return bulkLog;
    } finally {
      LOGGER.debug("exit from setCallbckStateForTasks()");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public BulkOperationResults<String, TaskanaException> setOwnerOfTasks(
      String owner, List<String> argTaskIds) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("entry to setOwnerOfTasks(owner = {}, tasks = {})", owner, argTaskIds);
    }
    BulkOperationResults<String, TaskanaException> bulkLog = new BulkOperationResults<>();
    if (argTaskIds == null || argTaskIds.isEmpty()) {
      return bulkLog;
    }
    // remove duplicates
    List<String> taskIds = argTaskIds.stream().distinct().collect(Collectors.toList());
    final int requestSize = taskIds.size();
    try {
      taskanaEngine.openConnection();
      // use only elements we are authorized for
      Pair<List<MinimalTaskSummary>, BulkLog> resultsPair = getMinimalTaskSummaries(taskIds);
      // set the Owner of these tasks we are authorized for
      List<MinimalTaskSummary> existingMinimalTaskSummaries = resultsPair.getLeft();
      taskIds =
          existingMinimalTaskSummaries.stream()
              .map(MinimalTaskSummary::getTaskId)
              .collect(Collectors.toList());
      bulkLog.addAllErrors(resultsPair.getRight());
      if (!taskIds.isEmpty()) {
        final int numberOfAffectedTasks = taskMapper.setOwnerOfTasks(owner, taskIds, Instant.now());
        if (numberOfAffectedTasks != taskIds.size()) { // all tasks were updated
          // check the outcome
          existingMinimalTaskSummaries = taskMapper.findExistingTasks(taskIds, null);
          bulkLog.addAllErrors(
              addExceptionsForTasksWhoseOwnerWasNotSet(owner, existingMinimalTaskSummaries));
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Received the Request to set owner on {} tasks, actually modified tasks = {}"
                    + ", could not set owner on {} tasks.",
                requestSize,
                numberOfAffectedTasks,
                bulkLog.getFailedIds().size());
          }
        }
      }
      return bulkLog;
    } finally {
      LOGGER.debug("exit from setOwnerOfTasks()");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public BulkOperationResults<String, TaskanaException> setPlannedPropertyOfTasks(
      Instant planned, List<String> argTaskIds) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to setPlannedPropertyOfTasks(planned = {}, tasks = {})", planned, argTaskIds);
    }

    BulkLog bulkLog = new BulkLog();
    if (argTaskIds == null || argTaskIds.isEmpty()) {
      return bulkLog;
    }
    try {
      taskanaEngine.openConnection();
      Pair<List<MinimalTaskSummary>, BulkLog> resultsPair = getMinimalTaskSummaries(argTaskIds);
      List<MinimalTaskSummary> tasksToModify = resultsPair.getLeft();
      bulkLog.addAllErrors(resultsPair.getRight());
      BulkLog errorsFromProcessing =
          serviceLevelHandler.setPlannedPropertyOfTasksImpl(planned, tasksToModify);
      bulkLog.addAllErrors(errorsFromProcessing);
      return bulkLog;
    } finally {
      LOGGER.debug("exit from setPlannedPropertyOfTasks");
      taskanaEngine.returnConnection();
    }
  }

  @Override
  public Task cancelTask(String taskId)
      throws TaskNotFoundException, InvalidStateException, NotAuthorizedException {
    LOGGER.debug("entry to cancelTask(task = {})", taskId);

    Task cancelledTask;

    try {
      taskanaEngine.openConnection();
      cancelledTask = terminateCancelCommonActions(taskId, TaskState.CANCELLED);

      if (HistoryEventManager.isHistoryEnabled()) {
        historyEventManager.createEvent(
            new TaskCancelledEvent(
                IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                cancelledTask,
                taskanaEngine.getEngine().getCurrentUserContext().getUserid()));
      }
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from cancelTask()");
    }

    return cancelledTask;
  }

  @Override
  public Task terminateTask(String taskId)
      throws TaskNotFoundException, InvalidStateException, NotAuthorizedException {
    LOGGER.debug("entry to terminateTask(task = {})", taskId);

    taskanaEngine.getEngine().checkRoleMembership(TaskanaRole.ADMIN, TaskanaRole.TASK_ADMIN);

    Task terminatedTask;

    try {
      taskanaEngine.openConnection();
      terminatedTask = terminateCancelCommonActions(taskId, TaskState.TERMINATED);

      if (HistoryEventManager.isHistoryEnabled()) {
        historyEventManager.createEvent(
            new TaskTerminatedEvent(
                IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                terminatedTask,
                taskanaEngine.getEngine().getCurrentUserContext().getUserid()));
      }

    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from terminateTask()");
    }
    return terminatedTask;
  }

  public List<String> findTasksIdsAffectedByClassificationChange(String classificationId) {
    LOGGER.debug(
        "entry to findTasksIdsAffectedByClassificationChange(classificationId = {})",
        classificationId);
    // tasks directly affected
    List<TaskSummary> tasksAffectedDirectly =
        createTaskQuery()
            .classificationIdIn(classificationId)
            .stateIn(TaskState.READY, TaskState.CLAIMED)
            .list();

    // tasks indirectly affected via attachments
    List<Pair<String, Instant>> affectedPairs =
        tasksAffectedDirectly.stream()
            .map(t -> Pair.of(t.getId(), t.getPlanned()))
            .collect(Collectors.toList());
    // tasks indirectly affected via attachments
    List<Pair<String, Instant>> taskIdsAndPlannedFromAttachments =
        attachmentMapper.findTaskIdsAndPlannedAffectedByClassificationChange(classificationId);

    List<String> taskIdsFromAttachments =
        taskIdsAndPlannedFromAttachments.stream().map(Pair::getLeft).collect(Collectors.toList());
    List<Pair<String, Instant>> filteredTaskIdsAndPlannedFromAttachments =
        taskIdsFromAttachments.isEmpty()
            ? new ArrayList<>()
            : taskMapper.filterTaskIdsForReadyAndClaimed(taskIdsFromAttachments);
    affectedPairs.addAll(filteredTaskIdsAndPlannedFromAttachments);
    //  sort all affected tasks according to the planned instant
    List<String> affectedTaskIds =
        affectedPairs.stream()
            .sorted(Comparator.comparing(Pair::getRight))
            .distinct()
            .map(Pair::getLeft)
            .collect(Collectors.toList());

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "the following tasks are affected by the update of classification {} : {}",
          classificationId,
          affectedTaskIds);
    }
    LOGGER.debug("exit from findTasksIdsAffectedByClassificationChange(). ");
    return affectedTaskIds;
  }

  public void refreshPriorityAndDueDatesOfTasksOnClassificationUpdate(
      List<String> taskIds, boolean serviceLevelChanged, boolean priorityChanged) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("entry to refreshPriorityAndDueDateOfTasks(tasks = {})", taskIds);
    }
    Pair<List<MinimalTaskSummary>, BulkLog> resultsPair = getMinimalTaskSummaries(taskIds);
    List<MinimalTaskSummary> tasks = resultsPair.getLeft();
    try {
      taskanaEngine.openConnection();
      Set<String> adminAccessIds =
          taskanaEngine.getEngine().getConfiguration().getRoleMap().get(TaskanaRole.ADMIN);
      if (adminAccessIds.contains(taskanaEngine.getEngine().getCurrentUserContext().getUserid())) {
        serviceLevelHandler.refreshPriorityAndDueDatesOfTasks(
            tasks, serviceLevelChanged, priorityChanged);
      } else {
        taskanaEngine.runAsAdmin(
            () -> {
              serviceLevelHandler.refreshPriorityAndDueDatesOfTasks(
                  tasks, serviceLevelChanged, priorityChanged);
              return null;
            });
      }
    } finally {
      LOGGER.debug("exit from refreshPriorityAndDueDateOfTasks");
      taskanaEngine.returnConnection();
    }
  }

  Pair<List<MinimalTaskSummary>, BulkLog> getMinimalTaskSummaries(List<String> argTaskIds) {
    BulkLog bulkLog = new BulkLog();
    // remove duplicates
    List<String> taskIds = argTaskIds.stream().distinct().collect(Collectors.toList());
    // get existing tasks
    List<MinimalTaskSummary> minimalTaskSummaries = taskMapper.findExistingTasks(taskIds, null);
    bulkLog.addAllErrors(addExceptionsForNonExistingTasksToBulkLog(taskIds, minimalTaskSummaries));
    Pair<List<MinimalTaskSummary>, BulkLog> filteredPair =
        filterTasksAuthorizedForAndLogErrorsForNotAuthorized(minimalTaskSummaries);
    bulkLog.addAllErrors(filteredPair.getRight());
    return new Pair<>(filteredPair.getLeft(), bulkLog);
  }

  Pair<List<MinimalTaskSummary>, BulkLog> filterTasksAuthorizedForAndLogErrorsForNotAuthorized(
      List<MinimalTaskSummary> existingTasks) {
    BulkLog bulkLog = new BulkLog();
    // check authorization only for non-admin or task-admin users
    if (taskanaEngine.getEngine().isUserInRole(TaskanaRole.ADMIN, TaskanaRole.TASK_ADMIN)) {
      return new Pair<>(existingTasks, bulkLog);
    } else {
      List<String> taskIds =
          existingTasks.stream().map(MinimalTaskSummary::getTaskId).collect(Collectors.toList());
      List<String> accessIds = taskanaEngine.getEngine().getCurrentUserContext().getAccessIds();
      List<String> taskIdsNotAuthorizedFor =
          taskMapper.filterTaskIdsNotAuthorizedFor(taskIds, accessIds);
      String userId = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
      for (String taskId : taskIdsNotAuthorizedFor) {
        bulkLog.addError(
            taskId,
            new NotAuthorizedException(
                String.format("User %s is not authorized for task %s ", userId, taskId), userId));
      }
      taskIds.removeAll(taskIdsNotAuthorizedFor);
      List<MinimalTaskSummary> tasksAuthorizedFor =
          existingTasks.stream()
              .filter(t -> taskIds.contains(t.getTaskId()))
              .collect(Collectors.toList());
      return new Pair<>(tasksAuthorizedFor, bulkLog);
    }
  }

  BulkLog addExceptionsForNonExistingTasksToBulkLog(
      List<String> requestTaskIds, List<MinimalTaskSummary> existingMinimalTaskSummaries) {
    BulkLog bulkLog = new BulkLog();
    List<String> nonExistingTaskIds = new ArrayList<>(requestTaskIds);
    List<String> existingTaskIds =
        existingMinimalTaskSummaries.stream()
            .map(MinimalTaskSummary::getTaskId)
            .collect(Collectors.toList());
    nonExistingTaskIds.removeAll(existingTaskIds);
    nonExistingTaskIds.forEach(
        taskId ->
            bulkLog.addError(taskId, new TaskNotFoundException(taskId, "Task was not found")));
    return bulkLog;
  }

  void removeNonExistingTasksFromTaskIdList(
      List<String> taskIds, BulkOperationResults<String, TaskanaException> bulkLog) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to removeNonExistingTasksFromTaskIdList(targetWbId = {}, taskIds = {})",
          taskIds,
          bulkLog);
    }

    Iterator<String> taskIdIterator = taskIds.iterator();
    while (taskIdIterator.hasNext()) {
      String currentTaskId = taskIdIterator.next();
      if (currentTaskId == null || currentTaskId.equals("")) {
        bulkLog.addError(
            "", new InvalidArgumentException("IDs with EMPTY or NULL value are not allowed."));
        taskIdIterator.remove();
      }
    }
    LOGGER.debug("exit from removeNonExistingTasksFromTaskIdList()");
  }

  List<TaskSummary> augmentTaskSummariesByContainedSummariesWithPartitioning(
      List<TaskSummaryImpl> taskSummaries) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to augmentTaskSummariesByContainedSummariesWithPartitioning(taskSummaries= {})",
          taskSummaries);
    }
    // splitting Augmentation into steps of maximal 32000 tasks
    // reason: DB2 has a maximum for parameters in a query
    List<TaskSummary> result =
        CollectionUtil.partitionBasedOnSize(taskSummaries, 32000).stream()
            .map(this::augmentTaskSummariesByContainedSummariesWithoutPartitioning)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

    LOGGER.debug("exit from to augmentTaskSummariesByContainedSummariesWithPartitioning()");
    return result;
  }

  private List<TaskSummaryImpl> augmentTaskSummariesByContainedSummariesWithoutPartitioning(
      List<TaskSummaryImpl> taskSummaries) {
    List<String> taskIds =
        taskSummaries.stream().map(TaskSummaryImpl::getId).distinct().collect(Collectors.toList());

    if (taskIds.isEmpty()) {
      taskIds = null;
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "augmentTaskSummariesByContainedSummariesWithoutPartitioning() with sublist {} "
              + "about to query for attachmentSummaries ",
          taskSummaries);
    }
    List<AttachmentSummaryImpl> attachmentSummaries =
        attachmentMapper.findAttachmentSummariesByTaskIds(taskIds);

    List<ClassificationSummary> classifications =
        findClassificationsForTasksAndAttachments(taskSummaries, attachmentSummaries);

    addClassificationSummariesToTaskSummaries(taskSummaries, classifications);
    addWorkbasketSummariesToTaskSummaries(taskSummaries);
    addAttachmentSummariesToTaskSummaries(taskSummaries, attachmentSummaries, classifications);

    return taskSummaries;
  }

  private BulkOperationResults<String, TaskanaException> completeTasks(
      List<String> taskIds, boolean forced) throws InvalidArgumentException {
    try {
      LOGGER.debug("entry to completeTasks(taskIds = {})", taskIds);
      taskanaEngine.openConnection();
      if (taskIds == null) {
        throw new InvalidArgumentException("TaskIds can't be used as NULL-Parameter.");
      }
      BulkOperationResults<String, TaskanaException> bulkLog = new BulkOperationResults<>();

      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      Stream<TaskSummaryImpl> filteredSummaries =
          filterNotExistingTaskIds(taskIds, bulkLog)
              .filter(task -> task.getState() != TaskState.COMPLETED)
              .filter(
                  addErrorToBulkLog(TaskServiceImpl::checkIfTaskIsTerminatedOrCancelled, bulkLog));
      if (!forced) {
        filteredSummaries =
            filteredSummaries.filter(
                addErrorToBulkLog(this::checkPreconditionsForCompleteTask, bulkLog));
      } else {
        String userId = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
        filteredSummaries =
            filteredSummaries.filter(
                addErrorToBulkLog(
                    summary -> {
                      if (taskIsNotClaimed(summary)) {
                        checkPreconditionsForClaimTask(summary, true);
                        claimActionsOnTask(summary, userId, now);
                      }
                    },
                    bulkLog));
      }

      updateTasksToBeCompleted(filteredSummaries, now);

      return bulkLog;
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from to completeTasks(taskIds = {})", taskIds);
    }
  }

  private Stream<TaskSummaryImpl> filterNotExistingTaskIds(
      List<String> taskIds, BulkOperationResults<String, TaskanaException> bulkLog) {

    Map<String, TaskSummaryImpl> taskSummaryMap =
        getTasksToChange(taskIds).stream()
            .collect(Collectors.toMap(TaskSummary::getId, TaskSummaryImpl.class::cast));
    return taskIds.stream()
        .map(id -> Pair.of(id, taskSummaryMap.get(id)))
        .filter(
            pair -> {
              if (pair.getRight() == null) {
                String taskId = pair.getLeft();
                bulkLog.addError(
                    taskId,
                    new TaskNotFoundException(
                        taskId, String.format("Task with id %s was not found.", taskId)));
                return false;
              }
              return true;
            })
        .map(Pair::getRight);
  }

  private static Predicate<TaskSummaryImpl> addErrorToBulkLog(
      CheckedConsumer<TaskSummaryImpl, TaskanaException> checkedConsumer,
      BulkOperationResults<String, TaskanaException> bulkLog) {
    return summary -> {
      try {
        checkedConsumer.accept(summary);
        return true;
      } catch (TaskanaException e) {
        bulkLog.addError(summary.getId(), e);
        return false;
      }
    };
  }

  private void checkConcurrencyAndSetModified(TaskImpl newTaskImpl, TaskImpl oldTaskImpl)
      throws ConcurrencyException {
    // TODO: not safe to rely only on different timestamps.
    // With fast execution below 1ms there will be no concurrencyException
    if (oldTaskImpl.getModified() != null
            && !oldTaskImpl.getModified().equals(newTaskImpl.getModified())
        || oldTaskImpl.getClaimed() != null
            && !oldTaskImpl.getClaimed().equals(newTaskImpl.getClaimed())
        || oldTaskImpl.getState() != null
            && !oldTaskImpl.getState().equals(newTaskImpl.getState())) {
      throw new ConcurrencyException("The task has already been updated by another user");
    }
    newTaskImpl.setModified(Instant.now());
  }

  private TaskImpl terminateCancelCommonActions(String taskId, TaskState targetState)
      throws NotAuthorizedException, TaskNotFoundException, InvalidStateException {
    if (taskId == null || taskId.isEmpty()) {
      throw new TaskNotFoundException(
          taskId, String.format("Task with id %s was not found.", taskId));
    }
    TaskImpl task = (TaskImpl) getTask(taskId);
    TaskState state = task.getState();
    if (state.isEndState()) {
      throw new InvalidStateException(
          String.format("Task with Id %s is already in an end state.", taskId));
    }

    Instant now = Instant.now();
    task.setModified(now);
    task.setCompleted(now);
    task.setState(targetState);
    taskMapper.update(task);
    LOGGER.debug(
        "Task '{}' cancelled by user '{}'.",
        taskId,
        taskanaEngine.getEngine().getCurrentUserContext().getUserid());
    return task;
  }

  private BulkOperationResults<String, TaskanaException> addExceptionsForTasksWhoseOwnerWasNotSet(
      String owner, List<MinimalTaskSummary> existingMinimalTaskSummaries) {
    BulkOperationResults<String, TaskanaException> bulkLog = new BulkOperationResults<>();

    for (MinimalTaskSummary taskSummary : existingMinimalTaskSummaries) {
      if (!owner.equals(taskSummary.getOwner())) { // owner was not set
        if (!TaskState.READY.equals(taskSummary.getTaskState())) { // due to invalid state
          bulkLog.addError(
              taskSummary.getTaskId(),
              new InvalidStateException(
                  String.format(
                      "Task with id %s is in state %s and not in state ready.",
                      taskSummary.getTaskId(), taskSummary.getTaskState())));
        } else { // due to unknown reason
          bulkLog.addError(
              taskSummary.getTaskId(),
              new UpdateFailedException(
                  String.format("Could not set owner of Task %s .", taskSummary.getTaskId())));
        }
      }
    }
    return bulkLog;
  }

  private Task claim(String taskId, boolean forceClaim)
      throws TaskNotFoundException, InvalidStateException, InvalidOwnerException,
          NotAuthorizedException {
    String userId = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
    LOGGER.debug(
        "entry to claim(id = {}, userId = {}, forceClaim = {})", taskId, userId, forceClaim);
    TaskImpl task;
    try {
      taskanaEngine.openConnection();
      task = (TaskImpl) getTask(taskId);
      Instant now = Instant.now();

      checkPreconditionsForClaimTask(task, forceClaim);
      claimActionsOnTask(task, userId, now);
      taskMapper.update(task);
      LOGGER.debug("Task '{}' claimed by user '{}'.", taskId, userId);
      if (HistoryEventManager.isHistoryEnabled()) {
        historyEventManager.createEvent(
            new TaskClaimedEvent(
                IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                task,
                taskanaEngine.getEngine().getCurrentUserContext().getUserid()));
      }
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from claim()");
    }
    return task;
  }

  private static void claimActionsOnTask(TaskSummaryImpl task, String userId, Instant now) {
    task.setOwner(userId);
    task.setModified(now);
    task.setClaimed(now);
    task.setRead(true);
    task.setState(TaskState.CLAIMED);
  }

  private static void completeActionsOnTask(TaskSummaryImpl task, String userId, Instant now) {
    task.setCompleted(now);
    task.setModified(now);
    task.setState(TaskState.COMPLETED);
    task.setOwner(userId);
  }

  private void checkPreconditionsForClaimTask(TaskSummary task, boolean forced)
      throws InvalidStateException, InvalidOwnerException {
    TaskState state = task.getState();
    if (!state.in(TaskState.READY, TaskState.CLAIMED)) {
      throw new InvalidStateException(
          String.format("Task with Id %s is already in an end state.", task.getId()));
    }
    if (!forced
        && state == TaskState.CLAIMED
        && !task.getOwner().equals(taskanaEngine.getEngine().getCurrentUserContext().getUserid())) {
      throw new InvalidOwnerException(
          String.format(
              "Task with id %s is already claimed by %s.", task.getId(), task.getOwner()));
    }
  }

  private static boolean taskIsNotClaimed(TaskSummary task) {
    return task.getClaimed() == null || task.getState() != TaskState.CLAIMED;
  }

  private static void checkIfTaskIsTerminatedOrCancelled(TaskSummary task)
      throws InvalidStateException {
    if (task.getState().in(TaskState.CANCELLED, TaskState.TERMINATED)) {
      throw new InvalidStateException(
          String.format(
              "Cannot complete task %s because it is in state %s.", task.getId(), task.getState()));
    }
  }

  private void checkPreconditionsForCompleteTask(TaskSummary task)
      throws InvalidStateException, InvalidOwnerException {
    if (taskIsNotClaimed(task)) {
      throw new InvalidStateException(
          String.format("Task with Id %s has to be claimed before.", task.getId()));
    } else if (!taskanaEngine
            .getEngine()
            .getCurrentUserContext()
            .getAccessIds()
            .contains(task.getOwner())
        && !taskanaEngine.getEngine().isUserInRole(TaskanaRole.ADMIN)) {
      throw new InvalidOwnerException(
          String.format(
              "Owner of task %s is %s, but current user is %s ",
              task.getId(),
              task.getOwner(),
              taskanaEngine.getEngine().getCurrentUserContext().getUserid()));
    }
  }

  private Task cancelClaim(String taskId, boolean forceUnclaim)
      throws TaskNotFoundException, InvalidStateException, InvalidOwnerException,
          NotAuthorizedException {
    String userId = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
    LOGGER.debug(
        "entry to cancelClaim(taskId = {}), userId = {}, forceUnclaim = {})",
        taskId,
        userId,
        forceUnclaim);
    TaskImpl task;
    try {
      taskanaEngine.openConnection();
      task = (TaskImpl) getTask(taskId);
      TaskState state = task.getState();
      if (state.isEndState()) {
        throw new InvalidStateException(
            String.format("Task with Id %s is already in an end state.", taskId));
      }
      if (state == TaskState.CLAIMED && !forceUnclaim && !userId.equals(task.getOwner())) {
        throw new InvalidOwnerException(
            String.format("Task with id %s is already claimed by %s.", taskId, task.getOwner()));
      }
      Instant now = Instant.now();
      task.setOwner(null);
      task.setModified(now);
      task.setClaimed(null);
      task.setRead(true);
      task.setState(TaskState.READY);
      taskMapper.update(task);
      LOGGER.debug("Task '{}' unclaimed by user '{}'.", taskId, userId);
      if (HistoryEventManager.isHistoryEnabled()) {
        historyEventManager.createEvent(
            new TaskClaimCancelledEvent(
                IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                task,
                taskanaEngine.getEngine().getCurrentUserContext().getUserid()));
      }
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from cancelClaim()");
    }
    return task;
  }

  private Task completeTask(String taskId, boolean isForced)
      throws TaskNotFoundException, InvalidOwnerException, InvalidStateException,
          NotAuthorizedException {
    String userId = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
    LOGGER.debug(
        "entry to completeTask(id = {}, userId = {}, isForced = {})", taskId, userId, isForced);
    TaskImpl task;
    try {
      taskanaEngine.openConnection();
      task = (TaskImpl) this.getTask(taskId);

      if (task.getState() == TaskState.COMPLETED) {
        return task;
      }

      checkIfTaskIsTerminatedOrCancelled(task);

      if (!isForced) {
        checkPreconditionsForCompleteTask(task);
      } else if (taskIsNotClaimed(task)) {
        task = (TaskImpl) this.forceClaim(taskId);
      }

      Instant now = Instant.now();
      completeActionsOnTask(task, userId, now);
      taskMapper.update(task);
      LOGGER.debug("Task '{}' completed by user '{}'.", taskId, userId);
      if (HistoryEventManager.isHistoryEnabled()) {
        historyEventManager.createEvent(
            new TaskCompletedEvent(
                IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                task,
                taskanaEngine.getEngine().getCurrentUserContext().getUserid()));
      }
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from completeTask()");
    }
    return task;
  }

  private void deleteTask(String taskId, boolean forceDelete)
      throws TaskNotFoundException, InvalidStateException, NotAuthorizedException {
    LOGGER.debug("entry to deleteTask(taskId = {} , forceDelete = {})", taskId, forceDelete);
    taskanaEngine.getEngine().checkRoleMembership(TaskanaRole.ADMIN);
    TaskImpl task;
    try {
      taskanaEngine.openConnection();
      task = (TaskImpl) getTask(taskId);

      if (!(task.getState().isEndState()) && !forceDelete) {
        throw new InvalidStateException(
            "Cannot delete Task " + taskId + " because it is not in an end state.");
      }
      if ((!task.getState().in(TaskState.TERMINATED, TaskState.CANCELLED))
          && CallbackState.CALLBACK_PROCESSING_REQUIRED.equals(task.getCallbackState())) {
        throw new InvalidStateException(
            String.format(
                "Task wit Id %s cannot be deleted because its callback is not yet processed",
                taskId));
      }

      attachmentMapper.deleteMultipleByTaskIds(Collections.singletonList(taskId));
      taskMapper.delete(taskId);

      if (taskanaEngine.getEngine().isHistoryEnabled()
          && taskanaEngine.getEngine().getConfiguration().isDeleteHistoryOnTaskDeletionEnabled()) {
        historyEventManager.deleteEvents(Collections.singletonList(taskId));
      }

      LOGGER.debug("Task {} deleted.", taskId);
    } finally {
      taskanaEngine.returnConnection();
      LOGGER.debug("exit from deleteTask().");
    }
  }

  private void removeSingleTaskForTaskDeletionById(
      BulkOperationResults<String, TaskanaException> bulkLog,
      List<MinimalTaskSummary> taskSummaries,
      Iterator<String> taskIdIterator) {
    LOGGER.debug("entry to removeSingleTask()");
    String currentTaskId = taskIdIterator.next();
    if (currentTaskId == null || currentTaskId.equals("")) {
      bulkLog.addError(
          "", new InvalidArgumentException("IDs with EMPTY or NULL value are not allowed."));
      taskIdIterator.remove();
    } else {
      MinimalTaskSummary foundSummary =
          taskSummaries.stream()
              .filter(taskSummary -> currentTaskId.equals(taskSummary.getTaskId()))
              .findFirst()
              .orElse(null);
      if (foundSummary == null) {
        bulkLog.addError(
            currentTaskId,
            new TaskNotFoundException(
                currentTaskId, String.format("Task with id %s was not found.", currentTaskId)));
        taskIdIterator.remove();
      } else if (!(foundSummary.getTaskState().isEndState())) {
        bulkLog.addError(currentTaskId, new InvalidStateException(currentTaskId));
        taskIdIterator.remove();
      } else {
        if ((!foundSummary.getTaskState().in(TaskState.CANCELLED, TaskState.TERMINATED))
            && CallbackState.CALLBACK_PROCESSING_REQUIRED.equals(foundSummary.getCallbackState())) {
          bulkLog.addError(
              currentTaskId,
              new InvalidStateException(
                  String.format(
                      "Task wit Id %s cannot be deleted because its callback is not yet processed",
                      currentTaskId)));
          taskIdIterator.remove();
        }
      }
    }
    LOGGER.debug("exit from removeSingleTask()");
  }

  private void removeSingleTaskForCallbackStateByExternalId(
      BulkOperationResults<String, TaskanaException> bulkLog,
      List<MinimalTaskSummary> taskSummaries,
      Iterator<String> externalIdIterator,
      CallbackState desiredCallbackState) {
    LOGGER.debug("entry to removeSingleTask()");
    String currentExternalId = externalIdIterator.next();
    if (currentExternalId == null || currentExternalId.equals("")) {
      bulkLog.addError(
          "", new InvalidArgumentException("IDs with EMPTY or NULL value are not allowed."));
      externalIdIterator.remove();
    } else {
      Optional<MinimalTaskSummary> foundSummary =
          taskSummaries.stream()
              .filter(taskSummary -> currentExternalId.equals(taskSummary.getExternalId()))
              .findFirst();
      if (foundSummary.isPresent()) {
        if (!desiredCallbackStateCanBeSetForFoundSummary(
            foundSummary.get(), desiredCallbackState)) {
          bulkLog.addError(currentExternalId, new InvalidStateException(currentExternalId));
          externalIdIterator.remove();
        }
      } else {
        bulkLog.addError(
            currentExternalId,
            new TaskNotFoundException(
                currentExternalId,
                String.format("Task with id %s was not found.", currentExternalId)));
        externalIdIterator.remove();
      }
    }
    LOGGER.debug("exit from removeSingleTask()");
  }

  private boolean desiredCallbackStateCanBeSetForFoundSummary(
      MinimalTaskSummary foundSummary, CallbackState desiredCallbackState) {

    CallbackState currentTaskCallbackState = foundSummary.getCallbackState();
    TaskState currentTaskState = foundSummary.getTaskState();

    switch (desiredCallbackState) {
      case CALLBACK_PROCESSING_COMPLETED:
        return currentTaskState.isEndState();

      case CLAIMED:
        if (!currentTaskState.equals(TaskState.CLAIMED)) {
          return false;
        } else {
          return currentTaskCallbackState.equals(CallbackState.CALLBACK_PROCESSING_REQUIRED);
        }

      case CALLBACK_PROCESSING_REQUIRED:
        return !currentTaskCallbackState.equals(CallbackState.CALLBACK_PROCESSING_COMPLETED);

      default:
        return false;
    }
  }

  private void standardSettings(TaskImpl task, Classification classification)
      throws InvalidArgumentException {
    TaskImpl task1 = task;
    LOGGER.debug("entry to standardSettings()");
    final Instant now = Instant.now();
    task1.setId(IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK));
    if (task1.getExternalId() == null) {
      task1.setExternalId(IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_EXT_TASK));
    }
    task1.setState(TaskState.READY);
    task1.setCreated(now);
    task1.setModified(now);
    task1.setRead(false);
    task1.setTransferred(false);

    String creator = taskanaEngine.getEngine().getCurrentUserContext().getUserid();
    if (taskanaEngine.getEngine().getConfiguration().isSecurityEnabled() && creator == null) {
      throw new SystemException(
          "TaskanaSecurity is enabled, but the current UserId is NULL while creating a Task.");
    }
    task1.setCreator(creator);

    // if no business process id is provided, a unique id is created.
    if (task1.getBusinessProcessId() == null) {
      task1.setBusinessProcessId(
          IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_BUSINESS_PROCESS));
    }

    // null in case of manual tasks
    if (task1.getPlanned() == null && (classification == null || task1.getDue() == null)) {
      task1.setPlanned(now);
    }
    if (classification != null) {
      task1 = serviceLevelHandler.updatePrioPlannedDueOfTask(task1, null, false);
    }

    if (task1.getName() == null && classification != null) {
      task1.setName(classification.getName());
    }

    if (task1.getDescription() == null && classification != null) {
      task1.setDescription(classification.getDescription());
    }
    try {
      attachmentHandler.insertNewAttachmentsOnTaskCreation(task);
    } catch (AttachmentPersistenceException e) {
      throw new SystemException(
          "Internal error when trying to insert new Attachments on Task Creation.", e);
    }
    LOGGER.debug("exit from standardSettings()");
  }

  private void setCallbackStateOnTaskCreation(TaskImpl task) throws InvalidArgumentException {
    Map<String, String> callbackInfo = task.getCallbackInfo();
    if (callbackInfo != null && callbackInfo.containsKey(Task.CALLBACK_STATE)) {
      String value = callbackInfo.get(Task.CALLBACK_STATE);
      if (value != null && !value.isEmpty()) {
        try {
          CallbackState state = CallbackState.valueOf(value);
          task.setCallbackState(state);
        } catch (Exception e) {
          LOGGER.warn(
              "Attempted to determine callback state from {} and caught exception", value, e);
          throw new InvalidArgumentException(
              String.format("Attempted to set callback state for task %s.", task.getId()), e);
        }
      }
    }
  }

  private void updateTasksToBeCompleted(Stream<TaskSummaryImpl> taskSummaries, Instant now) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("entry to updateTasksToBeCompleted()");
    }
    List<String> taskIds = new ArrayList<>();
    List<String> updateClaimedTaskIds = new ArrayList<>();
    List<TaskSummary> taskSummaryList =
        taskSummaries
            .peek(
                summary ->
                    completeActionsOnTask(
                        summary,
                        taskanaEngine.getEngine().getCurrentUserContext().getUserid(),
                        now))
            .peek(summary -> taskIds.add(summary.getId()))
            .peek(
                summary -> {
                  if (summary.getClaimed().equals(now)) {
                    updateClaimedTaskIds.add(summary.getId());
                  }
                })
            .collect(Collectors.toList());
    TaskSummary claimedReference =
        taskSummaryList.stream()
            .filter(summary -> updateClaimedTaskIds.contains(summary.getId()))
            .findFirst()
            .orElse(null);

    if (!taskSummaryList.isEmpty()) {
      taskMapper.updateCompleted(taskIds, taskSummaryList.get(0));
      if (!updateClaimedTaskIds.isEmpty()) {
        taskMapper.updateClaimed(updateClaimedTaskIds, claimedReference);
      }
      if (HistoryEventManager.isHistoryEnabled()) {
        createTasksCompletedEvents(taskSummaryList);
      }
    }
    LOGGER.debug("exit from updateTasksToBeCompleted()");
  }

  private void addClassificationSummariesToTaskSummaries(
      List<TaskSummaryImpl> tasks, List<ClassificationSummary> classifications) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to addClassificationSummariesToTaskSummaries(tasks = {}, classifications = {})",
          tasks,
          classifications);
    }

    if (tasks == null || tasks.isEmpty()) {
      LOGGER.debug("exit from addClassificationSummariesToTaskSummaries()");
      return;
    }
    // assign query results to appropriate tasks.
    for (TaskSummaryImpl task : tasks) {
      String classificationId = task.getClassificationSummary().getId();
      ClassificationSummary classificationSummary =
          classifications.stream()
              .filter(c -> c.getId().equals(classificationId))
              .findFirst()
              .orElse(null);
      if (classificationSummary == null) {
        throw new SystemException(
            "Did not find a Classification for task (Id="
                + task.getId()
                + ",classification="
                + task.getClassificationSummary().getId()
                + ")");
      }
      // set the classification on the task object
      task.setClassificationSummary(classificationSummary);
    }
    LOGGER.debug("exit from addClassificationSummariesToTaskSummaries()");
  }

  private List<ClassificationSummary> findClassificationsForTasksAndAttachments(
      List<TaskSummaryImpl> taskSummaries, List<AttachmentSummaryImpl> attachmentSummaries) {
    LOGGER.debug("entry to findClassificationsForTasksAndAttachments()");
    if (taskSummaries == null || taskSummaries.isEmpty()) {
      return new ArrayList<>();
    }

    Set<String> classificationIdSet =
        taskSummaries.stream()
            .map(t -> t.getClassificationSummary().getId())
            .collect(Collectors.toSet());

    if (attachmentSummaries != null && !attachmentSummaries.isEmpty()) {
      for (AttachmentSummaryImpl att : attachmentSummaries) {
        classificationIdSet.add(att.getClassificationSummary().getId());
      }
    }
    LOGGER.debug("exit from findClassificationsForTasksAndAttachments()");
    return queryClassificationsForTasksAndAttachments(classificationIdSet);
  }

  private List<ClassificationSummary> findClassificationForTaskImplAndAttachments(
      TaskImpl task, List<AttachmentImpl> attachmentImpls) {
    LOGGER.debug("entry to transferBulk()");
    Set<String> classificationIdSet =
        new HashSet<>(Collections.singletonList(task.getClassificationSummary().getId()));
    if (attachmentImpls != null && !attachmentImpls.isEmpty()) {
      for (AttachmentImpl att : attachmentImpls) {
        classificationIdSet.add(att.getClassificationSummary().getId());
      }
    }
    LOGGER.debug("exit from findClassificationForTaskImplAndAttachments()");
    return queryClassificationsForTasksAndAttachments(classificationIdSet);
  }

  private List<ClassificationSummary> queryClassificationsForTasksAndAttachments(
      Set<String> classificationIdSet) {

    String[] classificationIdArray = classificationIdSet.toArray(new String[0]);

    LOGGER.debug(
        "getClassificationsForTasksAndAttachments() about to query classifications and exit");
    // perform classification query
    return this.classificationService
        .createClassificationQuery()
        .idIn(classificationIdArray)
        .list();
  }

  private void addWorkbasketSummariesToTaskSummaries(List<TaskSummaryImpl> taskSummaries) {
    LOGGER.debug("entry to addWorkbasketSummariesToTaskSummaries()");
    if (taskSummaries == null || taskSummaries.isEmpty()) {
      return;
    }
    // calculate parameters for workbasket query: workbasket keys
    String[] workbasketIdArray =
        taskSummaries.stream()
            .map(t -> t.getWorkbasketSummary().getId())
            .distinct()
            .toArray(String[]::new);
    LOGGER.debug("addWorkbasketSummariesToTaskSummaries() about to query workbaskets");
    WorkbasketQueryImpl query = (WorkbasketQueryImpl) workbasketService.createWorkbasketQuery();
    query.setUsedToAugmentTasks(true);

    List<WorkbasketSummary> workbaskets = query.idIn(workbasketIdArray).list();
    Iterator<TaskSummaryImpl> taskIterator = taskSummaries.iterator();
    while (taskIterator.hasNext()) {
      TaskSummaryImpl task = taskIterator.next();
      String workbasketId = task.getWorkbasketSummaryImpl().getId();

      WorkbasketSummary workbasketSummary =
          workbaskets.stream()
              .filter(x -> workbasketId != null && workbasketId.equals(x.getId()))
              .findFirst()
              .orElse(null);
      if (workbasketSummary == null) {
        LOGGER.warn("Could not find a Workbasket for task {}.", task.getId());
        taskIterator.remove();
        continue;
      }

      task.setWorkbasketSummary(workbasketSummary);
    }
    LOGGER.debug("exit from addWorkbasketSummariesToTaskSummaries()");
  }

  private void addAttachmentSummariesToTaskSummaries(
      List<TaskSummaryImpl> taskSummaries,
      List<AttachmentSummaryImpl> attachmentSummaries,
      List<ClassificationSummary> classifications) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to addAttachmentSummariesToTaskSummaries(taskSummaries = {}, "
              + "attachmentSummaries = {}, classifications = {})",
          taskSummaries,
          attachmentSummaries,
          classifications);
    }

    if (taskSummaries == null || taskSummaries.isEmpty()) {
      return;
    }

    // augment attachment summaries by classification summaries
    // Note:
    // the mapper sets for each Attachment summary the property classificationSummary.key from the
    // CLASSIFICATION_KEY property in the DB
    addClassificationSummariesToAttachmentSummaries(
        attachmentSummaries, taskSummaries, classifications);
    // assign attachment summaries to task summaries
    for (TaskSummaryImpl task : taskSummaries) {
      for (AttachmentSummaryImpl attachment : attachmentSummaries) {
        if (attachment.getTaskId() != null && attachment.getTaskId().equals(task.getId())) {
          task.addAttachmentSummary(attachment);
        }
      }
    }

    LOGGER.debug("exit from addAttachmentSummariesToTaskSummaries()");
  }

  private void addClassificationSummariesToAttachmentSummaries(
      List<AttachmentSummaryImpl> attachmentSummaries,
      List<TaskSummaryImpl> taskSummaries,
      List<ClassificationSummary> classifications) {
    LOGGER.debug("entry to addClassificationSummariesToAttachmentSummaries()");
    // prereq: in each attachmentSummary, the classificationSummary.key property is set.
    if (attachmentSummaries == null
        || attachmentSummaries.isEmpty()
        || taskSummaries == null
        || taskSummaries.isEmpty()) {
      LOGGER.debug("exit from addClassificationSummariesToAttachmentSummaries()");
      return;
    }
    // iterate over all attachment summaries an add the appropriate classification summary to each
    for (AttachmentSummaryImpl att : attachmentSummaries) {
      String classificationId = att.getClassificationSummary().getId();
      ClassificationSummary classificationSummary =
          classifications.stream()
              .filter(x -> classificationId != null && classificationId.equals(x.getId()))
              .findFirst()
              .orElse(null);
      if (classificationSummary == null) {
        throw new SystemException("Could not find a Classification for attachment " + att);
      }
      att.setClassificationSummary(classificationSummary);
    }
    LOGGER.debug("exit from addClassificationSummariesToAttachmentSummaries()");
  }

  private List<Attachment> addClassificationSummariesToAttachments(
      List<AttachmentImpl> attachmentImpls, List<ClassificationSummary> classifications) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to addClassificationSummariesToAttachments(targetWbId = {}, taskIds = {})",
          attachmentImpls,
          classifications);
    }

    if (attachmentImpls == null || attachmentImpls.isEmpty()) {
      LOGGER.debug("exit from addClassificationSummariesToAttachments()");
      return new ArrayList<>();
    }

    List<Attachment> result = new ArrayList<>();
    for (AttachmentImpl att : attachmentImpls) {
      // find the associated task to use the correct domain
      ClassificationSummary classificationSummary =
          classifications.stream()
              .filter(c -> c != null && c.getId().equals(att.getClassificationSummary().getId()))
              .findFirst()
              .orElse(null);

      if (classificationSummary == null) {
        throw new SystemException("Could not find a Classification for attachment " + att);
      }
      att.setClassificationSummary(classificationSummary);
      result.add(att);
    }
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("exit from addClassificationSummariesToAttachments(), returning {}", result);
    }

    return result;
  }

  private TaskImpl initUpdatedTask(
      Map<TaskCustomField, String> customFieldsToUpdate, TaskCustomPropertySelector fieldSelector) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to initUpdatedTask(customFieldsToUpdate = {}, fieldSelector = {})",
          customFieldsToUpdate,
          fieldSelector);
    }

    TaskImpl newTask = new TaskImpl();
    newTask.setModified(Instant.now());

    for (Map.Entry<TaskCustomField, String> entry : customFieldsToUpdate.entrySet()) {
      TaskCustomField key = entry.getKey();
      fieldSelector.setCustomProperty(key, true);
      newTask.setCustomAttribute(key, entry.getValue());
    }
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("exit from initUpdatedTask(), returning {}", newTask);
    }

    return newTask;
  }

  private void validateCustomFields(Map<TaskCustomField, String> customFieldsToUpdate)
      throws InvalidArgumentException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "entry to validateCustomFields(customFieldsToUpdate = {})", customFieldsToUpdate);
    }

    if (customFieldsToUpdate == null || customFieldsToUpdate.isEmpty()) {
      throw new InvalidArgumentException(
          "The customFieldsToUpdate argument to updateTasks must not be empty.");
    }
    LOGGER.debug("exit from validateCustomFields()");
  }

  private List<TaskSummary> getTasksToChange(List<String> taskIds) {
    return createTaskQuery().idIn(taskIds.toArray(new String[0])).list();
  }

  private List<TaskSummary> getTasksToChange(ObjectReference selectionCriteria) {
    return createTaskQuery()
        .primaryObjectReferenceCompanyIn(selectionCriteria.getCompany())
        .primaryObjectReferenceSystemIn(selectionCriteria.getSystem())
        .primaryObjectReferenceSystemInstanceIn(selectionCriteria.getSystemInstance())
        .primaryObjectReferenceTypeIn(selectionCriteria.getType())
        .primaryObjectReferenceValueIn(selectionCriteria.getValue())
        .list();
  }

  private void standardUpdateActions(TaskImpl oldTaskImpl, TaskImpl newTaskImpl)
      throws InvalidArgumentException, InvalidStateException, ClassificationNotFoundException {

    if (oldTaskImpl.getExternalId() == null
        || !(oldTaskImpl.getExternalId().equals(newTaskImpl.getExternalId()))) {
      throw new InvalidArgumentException(
          "A task's external Id cannot be changed via update of the task");
    }

    String newWorkbasketKey = newTaskImpl.getWorkbasketKey();
    if (newWorkbasketKey != null && !newWorkbasketKey.equals(oldTaskImpl.getWorkbasketKey())) {
      throw new InvalidArgumentException(
          "A task's Workbasket cannot be changed via update of the task");
    }

    if (newTaskImpl.getClassificationSummary() == null) {
      newTaskImpl.setClassificationSummary(oldTaskImpl.getClassificationSummary());
    }

    updateClassificationSummary(newTaskImpl, oldTaskImpl);

    TaskImpl newTaskImpl1 =
        serviceLevelHandler.updatePrioPlannedDueOfTask(newTaskImpl, oldTaskImpl, false);

    // if no business process id is provided, use the id of the old task.
    if (newTaskImpl1.getBusinessProcessId() == null) {
      newTaskImpl1.setBusinessProcessId(oldTaskImpl.getBusinessProcessId());
    }

    // owner can only be changed if task is in state ready
    boolean isOwnerChanged = !Objects.equals(newTaskImpl1.getOwner(), oldTaskImpl.getOwner());
    if (isOwnerChanged && oldTaskImpl.getState() != TaskState.READY) {
      throw new InvalidStateException(
          String.format(
              "Task with id %s is in state %s and not in state ready.",
              oldTaskImpl.getId(), oldTaskImpl.getState()));
    }
  }

  private void updateClassificationSummary(TaskImpl newTaskImpl, TaskImpl oldTaskImpl)
      throws ClassificationNotFoundException {
    ClassificationSummary oldClassificationSummary = oldTaskImpl.getClassificationSummary();
    ClassificationSummary newClassificationSummary = newTaskImpl.getClassificationSummary();
    if (newClassificationSummary == null) {
      newClassificationSummary = oldClassificationSummary;
    }

    if (!oldClassificationSummary.getKey().equals(newClassificationSummary.getKey())) {
      Classification newClassification =
          this.classificationService.getClassification(
              newClassificationSummary.getKey(), newTaskImpl.getWorkbasketSummary().getDomain());
      newClassificationSummary = newClassification.asSummary();
      newTaskImpl.setClassificationSummary(newClassificationSummary);
    }
  }

  private void createTasksCompletedEvents(List<? extends TaskSummary> taskSummaries) {
    taskSummaries.forEach(
        task ->
            historyEventManager.createEvent(
                new TaskCompletedEvent(
                    IdGenerator.generateWithPrefix(IdGenerator.ID_PREFIX_TASK_HISTORY_EVENT),
                    task,
                    taskanaEngine.getEngine().getCurrentUserContext().getUserid())));
  }
}
