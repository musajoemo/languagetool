/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.View;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.tools.LanguageIdentifierTools;

/**
 * Support for associating a LanguageTool instance and a JTextComponent
 *
 * @author Panagiotis Minos
 */
class LanguageToolSupport implements Runnable {

  private JLanguageTool languageTool;
  private JTextComponent textComponent;
  // a red color highlight painter for marking spelling errors
  private HighlightPainter rhp;
  // a blue color highlight painter for marking grammar errors
  private HighlightPainter bhp;
  private List<RuleMatch> ruleMatches;
  private ArrayList<Span> documentSpans;
  private ScheduledExecutorService gcExecutor;
  private DocumentListener documentListener;
  private MouseListener mouseListener;
  private ActionListener actionListener;
  private int delay = 1500;//ms
  private AtomicInteger check;
  private boolean popupMenuEnabled = true;
  private boolean backgroundCheckEnabled = true;
  private final ResourceBundle messages;
  private Configuration config;
  private Language currentLanguage;
  private static final String CONFIG_FILE = ".languagetool.cfg";
  private final Map<Language, ConfigurationDialog> configDialogs = new HashMap<>();
  private JFrame frame;
  
  /**
   * LanguageTool support for a JTextComponent
   *
   * @param textComponent a JTextComponent
   */
  public LanguageToolSupport(JFrame frame, JTextComponent textComponent) {
    this.frame = frame;
    this.textComponent = textComponent;
    this.messages = JLanguageTool.getMessageBundle();
    init();
  }

  private Language getDefaultLanguage() {
    if (config.getLanguage() != null) {
      return config.getLanguage();
    } else {
      return Language.getLanguageForLocale(Locale.getDefault());
    }
  }

  private void warmUpChecker() {
    // Warm-up: we have a lot of lazy init in LT, which causes the first check to
    // be very slow (several seconds) for languages with a lot of data and a lot of 
    // rules. We just assume that the default language is the language that the user
    // often uses and init the LT object for that now, not just when it's first used.
    // This makes the first check feel much faster:
    getCurrentLanguageTool();
  }

  ConfigurationDialog getCurrentConfigDialog() {
    Language language = this.currentLanguage;
    final ConfigurationDialog configDialog;
    if (configDialogs.containsKey(language)) {
      configDialog = configDialogs.get(language);
    } else {
      configDialog = new ConfigurationDialog(frame, false);
      configDialog.setMotherTongue(config.getMotherTongue());
      configDialog.setDisabledRules(config.getDisabledRuleIds());
      configDialog.setEnabledRules(config.getEnabledRuleIds());
      configDialog.setDisabledCategories(config.getDisabledCategoryNames());
      configDialog.setRunServer(config.getRunServer());
      configDialog.setServerPort(config.getServerPort());
      configDialog.setUseGUIConfig(config.getUseGUIConfig());
      configDialogs.put(language, configDialog);
    }
    return configDialog;
  }

  private void getCurrentLanguageTool() {

    try {
      config = new Configuration(new File(System.getProperty("user.home")), CONFIG_FILE, currentLanguage);
      final ConfigurationDialog configDialog = getCurrentConfigDialog();
      languageTool = new JLanguageTool(currentLanguage, configDialog.getMotherTongue());
      languageTool.activateDefaultPatternRules();
      languageTool.activateDefaultFalseFriendRules();
      final Set<String> disabledRules = configDialog.getDisabledRuleIds();
      if (disabledRules != null) {
        for (final String ruleId : disabledRules) {
          languageTool.disableRule(ruleId);
        }
      }
      final Set<String> disabledCategories = configDialog.getDisabledCategoryNames();
      if (disabledCategories != null) {
        for (final String categoryName : disabledCategories) {
          languageTool.disableCategory(categoryName);
        }
      }
      final Set<String> enabledRules = configDialog.getEnabledRuleIds();
      if (enabledRules != null) {
        for (String ruleName : enabledRules) {
          languageTool.enableDefaultOffRule(ruleName);
          languageTool.enableRule(ruleName);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  private void init() {
    LanguageIdentifierTools.addLtProfiles();
    try {
      config = new Configuration(new File(System.getProperty("user.home")), CONFIG_FILE, null);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    currentLanguage = getDefaultLanguage();
    warmUpChecker();
    rhp = new HighlightPainter(Color.red);
    bhp = new HighlightPainter(Color.blue);
    ruleMatches = new ArrayList();
    documentSpans = new ArrayList();

    gcExecutor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.setName(t.getName() + "-lt-background");
        return t;
      }
    });

    check = new AtomicInteger(0);

    this.textComponent.getDocument().addDocumentListener(documentListener = new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        recalculateSpans(e.getOffset(), e.getLength(), false);
        if (backgroundCheckEnabled) {
          checkDelayed();
        }
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        recalculateSpans(e.getOffset(), e.getLength(), true);
        if (backgroundCheckEnabled) {
          checkDelayed();
        }
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        if (backgroundCheckEnabled) {
          checkDelayed();
        }
      }
    });

    this.textComponent.addMouseListener(mouseListener = new MouseListener() {
      @Override
      public void mouseClicked(MouseEvent me) {
      }

      @Override
      public void mousePressed(MouseEvent me) {
        if (me.isPopupTrigger()) {
          showPopup(me);
        }
      }

      @Override
      public void mouseReleased(MouseEvent me) {
        if (me.isPopupTrigger()) {
          showPopup(me);
        }
      }

      @Override
      public void mouseEntered(MouseEvent me) {
      }

      @Override
      public void mouseExited(MouseEvent me) {
      }
    });

    actionListener = new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        _actionPerformed(e);
      }
    };
    if (backgroundCheckEnabled) {
      checkImmediately();
    }
  }

  /**
   *
   * @return
   */
  public int getDelay() {
    return delay;
  }

  /**
   *
   * @param delay
   */
  public void setDelay(int delay) {
    this.delay = delay;
  }

  /**
   *
   * @return
   */
  public boolean isPopupMenuEnabled() {
    return popupMenuEnabled;
  }

  /**
   *
   * @param popupMenuEnabled
   */
  public void setPopupMenuEnabled(boolean popupMenuEnabled) {
    if (this.popupMenuEnabled == popupMenuEnabled) {
      return;
    }
    this.popupMenuEnabled = popupMenuEnabled;
    if (popupMenuEnabled) {
      textComponent.addMouseListener(mouseListener);
    } else {
      textComponent.removeMouseListener(mouseListener);
    }
  }

  public boolean isBackgroundCheckEnabled() {
    return backgroundCheckEnabled;
  }

  public void setBackgroundCheckEnabled(boolean backgroundCheckEnabled) {
    if (this.backgroundCheckEnabled == backgroundCheckEnabled) {
      return;
    }
    this.backgroundCheckEnabled = backgroundCheckEnabled;
    if (backgroundCheckEnabled) {
      checkImmediately();
    }
  }

  /**
   *
   * @param languageTool
   */
  public void setLanguageTool(JLanguageTool languageTool) {
    this.languageTool = languageTool;
    if (backgroundCheckEnabled) {
      checkImmediately();
    }
  }
  public void setLanguage(Language language) {
    this.currentLanguage= language;
    getCurrentLanguageTool();
    if (backgroundCheckEnabled) {
      checkImmediately();
    }    
  }
  public Configuration getConfig() {
    return config;
  }

  public JLanguageTool getLanguageTool() {
    return languageTool;
  }

  private void disableRule(String rule) {
    languageTool.disableRule(rule);
    updateHighlights(rule);
  }

  private void enableRule(String rule) {
    languageTool.enableRule(rule);
    checkImmediately();
  }

  private void showPopup(MouseEvent event) {
    if (documentSpans.isEmpty()) {
      return;
    }

    int offset = this.textComponent.viewToModel(event.getPoint());
    for (int i = 0; i < documentSpans.size(); i++) {
      final Span span = documentSpans.get(i);
      if (span.end > span.start) {
        if ((span.start <= offset) && (offset < span.end)) {
          JPopupMenu popup = new JPopupMenu("Grammar Menu");
          JLabel msgItem = new JLabel(span.msg);
          msgItem.setToolTipText(span.desc);
          msgItem.setBorder(new JMenuItem().getBorder());
          popup.add(msgItem);
          popup.add(new JSeparator());
          // TODO: i18n
          JMenuItem moreItem = new JMenuItem("More...");
          moreItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
              showDialog(textComponent, span.msg, span.desc, span.url);
            }
          });
          popup.add(moreItem);
          JMenuItem ignoreItem = new JMenuItem(messages.getString("guiOOoIgnoreButton"));

          ignoreItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
              disableRule(span.rule);
            }
          });
          popup.add(ignoreItem);
          if (!this.languageTool.getDisabledRules().isEmpty()) {
            // TODO: i18n
            JMenu activateRuleItem = new JMenu("Activate Rule");
            int count = 0;
            for (String ruleId : languageTool.getDisabledRules()) {
              Rule rule = getRuleForId(ruleId);
              if (rule == null) {
                continue;
              }
              if (rule.isDefaultOff()) {
                continue;
              }
              final String id = rule.getId();
              JMenuItem ruleItem = new JMenuItem(rule.getDescription());
              ruleItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                  enableRule(id);
                }
              });
              activateRuleItem.add(ruleItem);
              count++;
            }
            if (count > 0) {
              popup.add(activateRuleItem);
            }
          }
          popup.add(new JSeparator());
          for (String r : span.replacement) {
            ReplaceMenuItem item = new ReplaceMenuItem(r, i);
            popup.add(item);
            item.addActionListener(actionListener);
          }
          textComponent.setCaretPosition(span.start);
          textComponent.moveCaretPosition(span.end);
          popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
              textComponent.setCaretPosition(span.start);
            }
          });
          popup.show(textComponent, event.getPoint().x, event.getPoint().y);
        }
      }
    }
  }

  private Rule getRuleForId(String ruleId) {
    final List<Rule> allRules = languageTool.getAllRules();
    for (Rule rule : allRules) {
      if (rule.getId().equals(ruleId)) {
        return rule;
      }
    }
    return null;
  }

  private void _actionPerformed(ActionEvent e) {
    ReplaceMenuItem src = (ReplaceMenuItem) e.getSource();

    Span xs = this.documentSpans.remove(src.idx);
    applySuggestion(e.getActionCommand(), xs.start, xs.end);
  }

  private void applySuggestion(String str, int start, int end) {
    if (end < start) {
      throw new IllegalArgumentException("end before start");
    }
    Document doc = this.textComponent.getDocument();
    if (doc != null) {
      try {
        if (doc instanceof AbstractDocument) {
          ((AbstractDocument) doc).replace(start, end - start, str, null);
        } else {
          doc.remove(start, end - start);
          doc.insertString(start, str, null);
        }
      } catch (BadLocationException e) {
        throw new IllegalArgumentException(e.getMessage());
      }
    }
  }

  /**
   *
   */
  public void checkDelayed() {
    check.getAndIncrement();
    gcExecutor.schedule(this, delay, TimeUnit.MILLISECONDS);
  }

  /**
   *
   */
  public void checkImmediately() {
    check.getAndIncrement();
    gcExecutor.schedule(this, 0, TimeUnit.MILLISECONDS);
  }

  @Override
  public void run() {

    int v = check.decrementAndGet();
    if (v != 0) {
      return;
    }
    try {
      _check();
    } catch (IOException ex) {
    }
  }

  /**
   *
   * @return @throws IOException
   */
  synchronized List<RuleMatch> _check() throws IOException {
    final List<RuleMatch> matches = this.languageTool.check(this.textComponent.getText());
    int v = check.get();
    if (v == 0) {
      if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            updateHighlights(matches);
          }
        });
      } else {
        updateHighlights(matches);
      }
    }
    return matches;
  }

  private void removeHighlights() {

    for (Highlighter.Highlight hl : textComponent.getHighlighter().getHighlights()) {
      if (hl.getPainter() == rhp || hl.getPainter() == bhp) {
        textComponent.getHighlighter().removeHighlight(hl);
      }
    }

  }

  private void recalculateSpans(int offset, int length, boolean remove) {
    if (length == 0) {
      return;
    }
    for (Span span : this.documentSpans) {
      if (offset >= span.end) {
        continue;
      }
      if (!remove) {
        if (offset <= span.start) {
          span.start += length;
        }
        span.end += length;
      } else {
        if (offset + length <= span.end) {
          if (offset > span.start) {
          } else if (offset + length <= span.start) {
            span.start -= length;
          } else {
            span.start = offset;
          }
          span.end -= length;
        } else {
          span.end -= Math.min(length, span.end - offset);
        }
      }
    }
    updateHighlights();
  }

  private void updateHighlights(String rule) {
    ArrayList<Span> spans = new ArrayList();
    ArrayList<RuleMatch> matches = new ArrayList();

    for (RuleMatch match : ruleMatches) {
      if (match.getRule().getId().equals(rule)) {
        continue;
      }
      matches.add(match);
      Span t = new Span();
      t.start = match.getFromPos();
      t.end = match.getToPos();
      t.msg = (match.getShortMessage() != null && !match.getShortMessage().isEmpty()) ? match.getShortMessage() : match.getMessage();
      t.msg = shortenComment(t.msg);
      t.desc = match.getMessage();
      t.replacement = new ArrayList();
      t.replacement.addAll(match.getSuggestedReplacements());
      t.spelling = match.getRule().isSpellingRule();
      t.rule = match.getRule().getId();
      t.url = match.getRule().getUrl();
      spans.add(t);
    }
    ruleMatches.clear();
    documentSpans.clear();
    ruleMatches.addAll(matches);
    documentSpans.addAll(spans);
    updateHighlights();
  }

  private void updateHighlights(List<RuleMatch> matches) {
    ArrayList<Span> spans = new ArrayList();
    for (RuleMatch match : matches) {
      Span t = new Span();
      t.start = match.getFromPos();
      t.end = match.getToPos();
      t.msg = (match.getShortMessage() != null && !match.getShortMessage().isEmpty()) ? match.getShortMessage() : match.getMessage();
      t.msg = shortenComment(t.msg);
      t.desc = match.getMessage();
      t.replacement = new ArrayList();
      t.replacement.addAll(match.getSuggestedReplacements());
      t.spelling = match.getRule().isSpellingRule();
      t.rule = match.getRule().getId();
      t.url = match.getRule().getUrl();
      spans.add(t);
    }
    ruleMatches.clear();
    documentSpans.clear();
    ruleMatches.addAll(matches);
    documentSpans.addAll(spans);
    updateHighlights();
  }

  // TODO: move to common
  private String shortenComment(String comment) {
    final int maxCommentLength = 100;
    if (comment.length() > maxCommentLength) {
      // if there is text in brackets, drop it (beginning at the end)
      while (comment.lastIndexOf(" [") > 0
              && comment.lastIndexOf("]") > comment.lastIndexOf(" [")
              && comment.length() > maxCommentLength) {
        comment = comment.substring(0, comment.lastIndexOf(" [")) + comment.substring(comment.lastIndexOf("]") + 1);
      }
      while (comment.lastIndexOf(" (") > 0
              && comment.lastIndexOf(")") > comment.lastIndexOf(" (")
              && comment.length() > maxCommentLength) {
        comment = comment.substring(0, comment.lastIndexOf(" (")) + comment.substring(comment.lastIndexOf(")") + 1);
      }
      // in case it's still not short enough, shorten at the end
      if (comment.length() > maxCommentLength) {
        comment = comment.substring(0, maxCommentLength - 1) + "…";
      }
    }
    return comment;
  }

  private void updateHighlights() {
    removeHighlights();

    Highlighter h = textComponent.getHighlighter();
    ArrayList<Span> spellErrors = new ArrayList();
    ArrayList<Span> grammarErrors = new ArrayList();

    for (Span span : documentSpans) {
      if (span.start == span.end) {
        continue;
      }
      if (span.spelling) {
        spellErrors.add(span);
      } else {
        grammarErrors.add(span);
      }
    }



    for (Span span : grammarErrors) {
      try {
        h.addHighlight(span.start, span.end, bhp);
      } catch (BadLocationException ex) {
        //Tools.showError(ex);
      }
    }
    for (Span span : spellErrors) {
      try {
        h.addHighlight(span.start, span.end, rhp);
      } catch (BadLocationException ex) {
        //Tools.showError(ex);
      }
    }
  }

  static void showDialog(Component parent, String title, String message, URL url) {
    int dialogWidth = 320;
    JTextPane textPane = new JTextPane();
    textPane.setEditable(false);
    textPane.setContentType("text/html");
    textPane.setBorder(BorderFactory.createEmptyBorder());
    textPane.setOpaque(false);
    textPane.setBackground(new Color(0, 0, 0, 0));
    message = message.replaceAll("<suggestion>", "<b>")
            .replaceAll("</suggestion>", "</b>");
    textPane.addHyperlinkListener(new HyperlinkListener() {
      @Override
      public void hyperlinkUpdate(HyperlinkEvent e) {
        if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
          if (Desktop.isDesktopSupported()) {
            try {
              Desktop.getDesktop().browse(e.getURL().toURI());
            } catch (Exception ex) {
            }
          }
        }
      }
    });
    textPane.setSize(dialogWidth, Short.MAX_VALUE);
    textPane.setText("<html>" + message + formatURL(url) + "</html>");
    JScrollPane scrollPane = new JScrollPane(textPane);
    scrollPane.setPreferredSize(
            new Dimension(dialogWidth, textPane.getPreferredSize().height));
    scrollPane.setBorder(BorderFactory.createEmptyBorder());

    JOptionPane.showMessageDialog(parent, scrollPane, title,
            JOptionPane.INFORMATION_MESSAGE);

  }

  private static String formatURL(URL url) {
    if (url == null) {
      return "";
    }
    return String.format("<br/><br/><a href=\"%s\">%s</a>",
            url.toExternalForm(), "external link");


  }

  private static class HighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {

    private static final BasicStroke OO_STROKE1 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{3.0f, 5.0f}, 2);
    private static final BasicStroke OO_STROKE2 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{1.0f, 3.0f}, 3);
    private static final BasicStroke OO_STROKE3 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{3.0f, 5.0f}, 6);
    private static final BasicStroke FIREFOX_STROKE1 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{2.0f, 4.0f}, 1);
    private static final BasicStroke FIREFOX_STROKE2 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{1.0f, 2.0f}, 2);
    private static final BasicStroke FIREFOX_STROKE3 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{2.0f, 4.0f}, 4);
    private static final BasicStroke ZIGZAG_STROKE1 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{1.0f, 1.0f}, 0);
    private static final BasicStroke ZIGZAG_STROKE2 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{1.0f, 1.0f}, 1);

    public HighlightPainter() {
      super(Color.blue);
    }

    public HighlightPainter(Color color) {
      super(color);
    }

    @Override
    public Shape paintLayer(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c, View view) {
      Rectangle rect;

      if (offs0 == view.getStartOffset() && offs1 == view.getEndOffset()) {
        if (bounds instanceof Rectangle) {
          rect = (Rectangle) bounds;
        } else {
          rect = bounds.getBounds();
        }
      } else {
        try {
          Shape shape = view.modelToView(offs0, Position.Bias.Forward, offs1, Position.Bias.Backward, bounds);
          rect = (shape instanceof Rectangle) ? (Rectangle) shape : shape.getBounds();
        } catch (BadLocationException e) {
          rect = null;
        }
      }

      if (rect != null) {
        Color color = getColor();

        if (color == null) {
          g.setColor(c.getSelectionColor());
        } else {
          g.setColor(color);
        }

        rect.width = Math.max(rect.width, 1);

        int descent = c.getFontMetrics(c.getFont()).getDescent();

        if (descent > 3) {
          drawCurvedLine(g, rect);
        } else if (descent > 2) {
          drawCurvedLine(g, rect);
          //drawZigZagLine(g, rect);
        } else {
          drawLine(g, rect);
        }
      }

      return rect;
    }

    private void drawCurvedLine(Graphics g, Rectangle rect) {
      int x1 = rect.x;
      int x2 = rect.x + rect.width;
      int y = rect.y + rect.height;
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setStroke(OO_STROKE1);
      g2.drawLine(x1, y - 1, x2, y - 1);
      g2.setStroke(OO_STROKE2);
      g2.drawLine(x1, y - 2, x2, y - 2);
      g2.setStroke(OO_STROKE3);
      g2.drawLine(x1, y - 3, x2, y - 3);
    }

    private void drawFCurvedLine(Graphics g, Rectangle rect) {
      int x1 = rect.x;
      int x2 = rect.x + rect.width;
      int y = rect.y + rect.height;
      Graphics2D g2 = (Graphics2D) g;
      //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setStroke(FIREFOX_STROKE1);
      g2.drawLine(x1, y - 1, x2, y - 1);
      g2.setStroke(FIREFOX_STROKE2);
      g2.drawLine(x1, y - 2, x2, y - 2);
      g2.setStroke(FIREFOX_STROKE3);
      g2.drawLine(x1, y - 3, x2, y - 3);
    }

    private void drawZigZagLine(Graphics g, Rectangle rect) {
      int x1 = rect.x;
      int x2 = rect.x + rect.width;
      int y = rect.y + rect.height;
      Graphics2D g2 = (Graphics2D) g;
      //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setStroke(ZIGZAG_STROKE1);
      g2.drawLine(x1, y - 1, x2, y - 1);
      g2.setStroke(ZIGZAG_STROKE2);
      g2.drawLine(x1, y - 2, x2, y - 2);
    }

    private void drawLine(Graphics g, Rectangle rect) {
      int x1 = rect.x;
      int x2 = rect.x + rect.width;
      int y = rect.y + rect.height;
      Graphics2D g2 = (Graphics2D) g;
      //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setStroke(ZIGZAG_STROKE1);
      g2.drawLine(x1, y - 1, x2, y - 1);
    }
  }

  private static class ReplaceMenuItem extends JMenuItem {

    private int idx;

    private ReplaceMenuItem(String name, int idx) {
      super(name);
      this.idx = idx;
    }
  }

  private static class Span {

    private int start;
    private int end;
    private String msg;
    private String desc;
    private ArrayList<String> replacement;
    private boolean spelling;
    private String rule;
    private URL url;
  }
}