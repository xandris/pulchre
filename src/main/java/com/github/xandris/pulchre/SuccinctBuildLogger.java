package com.github.xandris.pulchre;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.LoggerManager;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.internal.CLibrary;
import org.fusesource.jansi.internal.Kernel32;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.fusesource.jansi.Ansi.ansi;
import static org.fusesource.jansi.internal.Kernel32.GetConsoleScreenBufferInfo;
import static org.fusesource.jansi.internal.Kernel32.GetStdHandle;
import static org.fusesource.jansi.internal.Kernel32.STD_OUTPUT_HANDLE;

@Component(role=EventSpy.class)
public class SuccinctBuildLogger extends AbstractEventSpy {
  @Requirement
  private Logger LOG;

  @Requirement
  private LoggerManager logManager;

  private final ActionQueue aq = new ActionQueue();
  private Display d;
  private PrintStream origOut;
  private PrintStream origErr;
  private ThreadLocalOutputStream out;
  private ThreadLocalOutputStream err;

  public SuccinctBuildLogger() {
  }

  void interceptStdStreams() {
    origOut = System.out;
    origErr = System.err;
    out = new ThreadLocalOutputStream();
    err = new ThreadLocalOutputStream();
    System.setOut(new PrintStream(out));
    System.setErr(new PrintStream(err));

    try {
      Class simpleLogger = Class.forName("org.slf4j.impl.SimpleLogger");
      Class simpleLoggerConfiguation = Class.forName("org.slf4j.impl.SimpleLoggerConfiguration");
      Class outputChoice = Class.forName("org.slf4j.impl.OutputChoice");
      Field simpleLogger_configParams = simpleLogger.getDeclaredField("CONFIG_PARAMS");
      Field simpleLoggerConfiguration_outputChoice = simpleLoggerConfiguation.getDeclaredField("outputChoice");
      Field outputChoice_targetPrintString = outputChoice.getDeclaredField("targetPrintStream");

      simpleLogger_configParams.setAccessible(true);
      simpleLoggerConfiguration_outputChoice.setAccessible(true);
      outputChoice_targetPrintString.setAccessible(true);

      Object conf = simpleLogger_configParams.get(null);
      Object oc = simpleLoggerConfiguration_outputChoice.get(conf);
      outputChoice_targetPrintString.set(oc, System.out);
    } catch(Throwable t) {
      // ignore
      LOG.error("oops", t);
    }
  }

  void restoreStdStreams() {
    out.set(origOut);
    out.set(origErr);
  }

  @Override
  public void init(Context context) throws Exception {
    super.init(context);
    try {
      aq.start();
      aq.put(() -> {
        this.d = new Display();
        if(this.d.isValid()) {
          interceptStdStreams();
          restoreStdStreams();
        }
      });
      display(Display::start);

    } catch(Throwable t) {
      LOG.error("Ooops", t);
    }
  }

  @Override
  public void close() throws Exception {
    super.close();
    display(Display::close);
    aq.stop();
  }

  private void display(Consumer<Display> r) {
    aq.put(()->r.accept(d));
  }

  private static Display.Project convert(MavenProject p) {
    return new Display.Project(p.toString(), p.getName());
  }

  private static Display.Status convert(ExecutionEvent.Type t) {
    switch(t) {
      case ProjectStarted:
      case ForkStarted:
      case ForkedProjectStarted:
        return Display.Status.BUILDING;
      case ProjectFailed:
      case ForkFailed:
      case ForkedProjectFailed:
        return Display.Status.FAILED;
      case ProjectSkipped:
        return Display.Status.SKIPPED;
      case ProjectSucceeded:
      case ForkSucceeded:
      case ForkedProjectSucceeded:
        return Display.Status.SUCCESS;
      default:
        throw new RuntimeException("Unexpected status type " + t.name());
    }
  }

  @Override
  public void onEvent(Object event) throws Exception {
    super.onEvent(event);
    if(event instanceof ExecutionEvent) {
      ExecutionEvent ee = (ExecutionEvent) event;
      //MavenProject proj = ee.getProject();
      //origOut.println(ee.getType() + (proj == null ? "" : " Project " + proj.getName()));
      MavenSession session = ee.getSession();
      switch(ee.getType()) {
        case SessionStarted: {
          session.getRequest().setLoggingLevel(0);
          List<Display.Project> projects = session.getProjectDependencyGraph().getSortedProjects().stream()
            .map(SuccinctBuildLogger::convert)
            .collect(toList());
          origOut.println(projects.stream().map(p->p.name).collect(joining(", ")));
          display(d->d.showProjects(projects));

          break;
        }
        case ProjectStarted:
        case ProjectSkipped:
        case ProjectFailed:
        case ProjectSucceeded: {
          Display.Project p = convert(ee.getProject());
          Display.Status s = convert(ee.getType());
          display(d->d.status(p, s));
          break;
        }
        case MojoStarted: {
          Display.Project p = convert(ee.getProject());
          MojoExecution me = ee.getMojoExecution();
          String id = me.getExecutionId();
          String goal = me.getGoal();
          String activity = id == null ? goal : id;
          display(d->d.activity(p, activity));
        }
      }
    }
  }

}

class ActionQueue {
  private static final Runnable POISON = ()->{};
  private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(20);
  private final Thread t;
  private volatile boolean stopped;

  ActionQueue() {
    t = new Thread(this::run);
  }

  void put(Runnable r) {
    while(true) {
      try {
        if(!stopped) {
          queue.offer(r, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        }
        return;
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }

  void start() {
    t.start();
  }

  void stop() {
    put(POISON);
    while(t.isAlive()) {
      try {
        t.join();
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }

  private void run() {
    try {
      while(true) {
        try {
          Runnable r = queue.take();
          if(r == POISON) {
            return;
          }
          try {
            r.run();
          } catch(Error e) {
            throw e;
          } catch(Throwable t) {
            // ignore
            t.printStackTrace();
          }
        } catch (InterruptedException e) {
          // ignore
        }
      }
    } finally {
      stopped = true;
    }
  }
}

class Display implements AutoCloseable {
  private static final int LABEL_PADDING = 3;

  class Label {
    String name;
    com.github.xandris.pulchre.Display.Status status;
    String activity;

    int x;
    int y;

    void draw() {
      char[] fill = new char[labelWidth];
      Arrays.fill(fill, ' ');
      String label = verbose && activity != null ? activity : status.label;
      if(stringWidth(label) > statusWidth) {
        label = label.substring(1, statusWidth-4) + "...";
      }
      System.out.print(ansi().cursor(1 + y, 1 + x).a(fill));
      System.out.print((status.bright ? ansi().fgBright(status.fg) : ansi().fg(status.fg))
        .cursor(1 + y, 1 + x + statusWidth - stringWidth(label)).a(label)
        .cursor(1 + y, 1 + x + statusWidth + 1).a(name)
        .reset());
    }

    int getMinWidth() {
      return stringWidth(name) + 1 + statusWidth;
    }
  }

  private static final int ACTIVITY_WIDTH = 12;
  private final Map<com.github.xandris.pulchre.Display.Project, com.github.xandris.pulchre.Display.Label> labelForProject = new HashMap<>();
  private boolean verbose = false;
  private int lastRow = 0;
  private int labelWidth;
  private int statusWidth;
  private TermSize size;

  Display() {
    int maxStatusWidth = 0;
    for(com.github.xandris.pulchre.Display.Status s : com.github.xandris.pulchre.Display.Status.values()) {
      maxStatusWidth = Math.max(maxStatusWidth, stringWidth(s.label));
    }
    statusWidth = verbose ? Math.max(maxStatusWidth, ACTIVITY_WIDTH) : maxStatusWidth;
    size = TermSize.getTermSize();
  }

  boolean isValid() {
    return size != null;
  }

  void start() {
    labelForProject.clear();
    System.out.print(ansi().eraseScreen());
    ExtendedTerminal.INSTANCE.localEcho(false);
    ExtendedTerminal.INSTANCE.showCursor(false);
    System.out.print(ansi().reset());
    Runtime.getRuntime().addShutdownHook(new Thread(()->{
      ExtendedTerminal.INSTANCE.showCursor(true);
      ExtendedTerminal.INSTANCE.localEcho(true);
      System.out.print(ansi().reset());
      System.out.flush();
    }));
  }

  @Override
  public void close() {
    System.out.println(ansi().cursor(lastRow+1, 0));
    ExtendedTerminal.INSTANCE.showCursor(true);
    ExtendedTerminal.INSTANCE.localEcho(true);
    System.out.print(ansi().reset());
    System.out.flush();
  }

  private static int ceilDiv(int n, int d) {
    int q = n/d;
    return q*d == n ? q : q+1;
  }

  void showProjects(List<com.github.xandris.pulchre.Display.Project> projects) {

    labelWidth = 0;
    List<com.github.xandris.pulchre.Display.Label> labels = new ArrayList<>(projects.size());

    for(com.github.xandris.pulchre.Display.Project project: projects) {
      com.github.xandris.pulchre.Display.Label l = new com.github.xandris.pulchre.Display.Label();
      l.name = project.name;
      l.status = com.github.xandris.pulchre.Display.Status.WAITING;
      labelWidth = Math.max(labelWidth, l.getMinWidth());

      labels.add(l);
      labelForProject.put(project, l);
    }

    labelWidth += LABEL_PADDING;

    int cols = size.width;
    int rows = ceilDiv(labels.size(), cols/labelWidth);
    lastRow = rows;
    int row = 0;
    int col = 0;

    for(com.github.xandris.pulchre.Display.Label l: labels) {
      l.x = col * labelWidth;
      l.y = row;
      ++row;
      if(row == rows) {
        row = 0;
        ++col;
      }
    }

    for(com.github.xandris.pulchre.Display.Label l : labels) {
      l.draw();
    }

    System.out.flush();
  }

  void status(com.github.xandris.pulchre.Display.Project project, com.github.xandris.pulchre.Display.Status status) {
    com.github.xandris.pulchre.Display.Label l = labelForProject.get(project);
    if(l == null) {
      return;
    }

    l.status = status;
    l.activity = null;
    l.draw();

    System.out.flush();
  }

  void activity(com.github.xandris.pulchre.Display.Project project, String activity) {
    com.github.xandris.pulchre.Display.Label l = labelForProject.get(project);
    if(l == null) {
      return;
    }

    l.activity = activity;
    l.draw();

    System.out.flush();
  }

  public static class Project {
    final String id;
    final String name;

    Project(String id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof com.github.xandris.pulchre.Display.Project)) return false;
      com.github.xandris.pulchre.Display.Project project = (com.github.xandris.pulchre.Display.Project) o;
      return Objects.equals(id, project.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id);
    }
  }

  enum Status {
    WAITING("", Ansi.Color.WHITE),
    BUILDING("\ud83d\udd04", Ansi.Color.YELLOW), // 0x1f504
    FAILED("\u274c", Ansi.Color.RED),
    SUCCESS("\u2705", Ansi.Color.GREEN),
    SKIPPED("\u2754", Ansi.Color.BLACK, true);
    final String label;
    final Ansi.Color fg;
    final boolean bright;

    Status(String label, Ansi.Color fg) {
      this(label, fg, false);
    }

    Status(String label, Ansi.Color fg, boolean bright) {
      this.label = label;
      this.fg = fg;
      this.bright = bright;
    }
  }

  public static int stringWidth(String s) {
    int sum = 0;
    int len = s.length();
    for(int i = 0; i < len;) {
      char c = s.charAt(i++);
      if((c & 0xf800) != 0xd800) {
        sum += charWidth(c);
        continue;
      }
      if((c & 0x400) != 0 || i==len) {
        System.out.println("oops1");
        throw new RuntimeException("Invalid UTF-16");
      }
      char c2 = s.charAt(i++);
      if((c2 & 0x400) == 0) {
        System.out.println("oops2");
        throw new RuntimeException("Invalid UTF-16");
      }
      sum += charWidth(((c & 0x3ff) << 10) | (c2 & 0x3ff) | 0x10000);
    }
    return sum;
  }

  public static int charWidth(int c) {
    return ((c & 0xff00) == 0x2700
      || (c >= 0x1f300 && c < 0x1f600)
    ) ? 2 : 1;
  }
}

class NoopOutputStream extends OutputStream {
  public static final NoopOutputStream INSTANCE = new NoopOutputStream();
  private NoopOutputStream() {}

  @Override public void write(byte[] b) { }
  @Override public void write(byte[] b, int off, int len) { }
  @Override public void write(int b) { }
}

class ThreadLocalOutputStream extends OutputStream {
  private static final ThreadLocal<OutputStream> OS = InheritableThreadLocal.withInitial(()->NoopOutputStream.INSTANCE);

  public void set(OutputStream os) {
    OS.set(os);
  }

  @Override
  public void write(byte[] b) throws IOException {
    OS.get().write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    OS.get().write(b, off, len);
  }

  @Override
  public void flush() throws IOException {
    OS.get().flush();
  }

  @Override
  public void close() throws IOException {
    OS.get().close();
  }

  @Override
  public void write(int b) throws IOException {
    OS.get().write(b);
  }
}

class ExtendedTerminal {
  public static final ExtendedTerminalThings INSTANCE;

  private static ExtendedTerminalThings makeInstance() {
    try {
      if(CLibrary.isatty(CLibrary.STDOUT_FILENO) != 0) {
        return new UnixExtendedTerminalThings();
      }
    } catch(Throwable t) {
      // ignore
    }

    return new ExtendedTerminalThings();
  }

  static {
    INSTANCE = makeInstance();
  }
}

class ExtendedTerminalThings {
  private static final byte[] CURSOR = {0x1b, 0x5b, 0x3f, 0x32, 0x35};
  private static final byte HIGH = 0x68;
  private static final byte LOW = 0x6c;

  ExtendedTerminalThings() {

  }

  public void localEcho(boolean on) {
  }

  public void showCursor(boolean on) {
    try {
      System.out.write(CURSOR);
      System.out.write(on ? HIGH : LOW);
    } catch (IOException e) {
      // ignore
    }
  }
}

class UnixExtendedTerminalThings extends ExtendedTerminalThings {
  private static final long ECHO = 0x8;
  private static final long ICANON = 0x100;

  private final boolean stdinIsTty;
  private final CLibrary.Termios stdinDefaults;

  public UnixExtendedTerminalThings() {
    stdinIsTty = CLibrary.isatty(CLibrary.STDIN_FILENO) != 0;
    stdinDefaults = new CLibrary.Termios();
    if(stdinIsTty) {
      CLibrary.tcgetattr(CLibrary.STDIN_FILENO, stdinDefaults);
    }
  }

  public void localEcho(boolean on) {
    if(stdinIsTty) {
      CLibrary.Termios tios = new CLibrary.Termios();
      CLibrary.tcgetattr(CLibrary.STDIN_FILENO, tios);
      if(on) {
        tios.c_lflag = stdinDefaults.c_lflag;
      } else {
        tios.c_lflag &= ~(ICANON | ECHO);
      }
      CLibrary.tcsetattr(CLibrary.STDIN_FILENO, CLibrary.TCSAFLUSH, tios);
    }
  }
}

class TermSize {
  final int width;
  final int height;

  TermSize(int width, int height) {
    this.width = width;
    this.height = height;
  }

  static TermSize getTermSize() {
    try {
      long l = GetStdHandle(STD_OUTPUT_HANDLE);
      Kernel32.CONSOLE_SCREEN_BUFFER_INFO info = new Kernel32.CONSOLE_SCREEN_BUFFER_INFO();
      if(GetConsoleScreenBufferInfo(l, info) != 0) {
        return new TermSize(info.window.width(), info.window.height());
      }
    } catch(Throwable ignore) {
      // ignore
    }

    try {
      CLibrary.WinSize ws = new CLibrary.WinSize();
      if(CLibrary.ioctl(STD_OUTPUT_HANDLE, CLibrary.TIOCGWINSZ, ws) == 0) {
        return new TermSize(ws.ws_col, ws.ws_row);
      }
    } catch(Throwable ignore) {
      // ignore
    }

    return null;
  }
}
