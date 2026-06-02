/***************************************************************************************
* Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* DiffTest is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include "uart.h"
#include "common.h"
#include <fcntl.h>
#include "stdlib.h"
#include <termios.h>
#include <unistd.h>

#define QUEUE_SIZE 1024
static char queue[QUEUE_SIZE] = {};
static int f = 0, r = 0;
static int saved_stdin_flags = -1;
static struct termios saved_termios;
static bool termios_saved = false;

static void restore_stdin() {
  if (saved_stdin_flags >= 0) {
    fcntl(STDIN_FILENO, F_SETFL, saved_stdin_flags);
    saved_stdin_flags = -1;
  }
  if (termios_saved) {
    tcsetattr(STDIN_FILENO, TCSANOW, &saved_termios);
    termios_saved = false;
  }
}

static void uart_enqueue(char ch) {
  int next = (r + 1) % QUEUE_SIZE;
  if (next != f) {
    // not full
    queue[r] = ch;
    r = next;
  }
}

static int uart_dequeue(void) {
  int k = 0;
  if (f != r) {
    k = queue[f];
    f = (f + 1) % QUEUE_SIZE;
  } else {
    static int last = 0;
    k = "root\n"[last++];
    if (last == 5)
      last = 0;
    // generate a random key every 1s for pal
    //k = -1;//"uiojkl"[rand()% 6];
  }
  return k;
}

uint32_t uptime(void);
uint8_t uart_getc() {
  static uint32_t lasttime = 0;
  static bool stdin_init = false;
  uint32_t now = uptime();

  uint8_t ch = -1;
  if (!stdin_init) {
    saved_stdin_flags = fcntl(STDIN_FILENO, F_GETFL, 0);
    if (saved_stdin_flags >= 0) {
      fcntl(STDIN_FILENO, F_SETFL, saved_stdin_flags | O_NONBLOCK);
    }
    if (isatty(STDIN_FILENO) && tcgetattr(STDIN_FILENO, &saved_termios) == 0) {
      struct termios raw = saved_termios;
      raw.c_lflag &= ~(ECHO | ICANON);
      raw.c_cc[VMIN] = 0;
      raw.c_cc[VTIME] = 0;
      tcsetattr(STDIN_FILENO, TCSANOW, &raw);
      termios_saved = true;
    }
    atexit(restore_stdin);
    stdin_init = true;
  }
  if (f != r) {
    return uart_dequeue();
  }
  if (read(STDIN_FILENO, &ch, 1) == 1) {
    uint8_t queued_ch;
    while (read(STDIN_FILENO, &queued_ch, 1) == 1) {
      uart_enqueue(queued_ch);
    }
    return ch;
  }
  if (now - lasttime > 60 * 1000) {
    // 1 minute
    eprintf(ANSI_COLOR_RED "now = %ds\n" ANSI_COLOR_RESET, now / 1000);
    lasttime = now;
  }
  // if (now > 4 * 3600 * 1000) { // 4 hours
  //   ch = uart_dequeue();
  // }
  return ch;
}

void uart_getc_legacy(uint8_t *ch) {
  static uint32_t lasttime = 0;
  uint32_t now = uptime();

  *ch = -1;
  if (now - lasttime > 60 * 1000) {
    // 1 minute
    eprintf(ANSI_COLOR_RED "now = %ds\n" ANSI_COLOR_RESET, now / 1000);
    lasttime = now;
  }
  if (now > 4 * 3600 * 1000) { // 4 hours
    *ch = uart_dequeue();
  }
}

static void preset_input() {
  char rtthread_cmd[128] = "memtrace\n";
  char init_cmd[128] =
      "2"             // choose PAL
      "jjjjjjjkkkkkk" // walk to enemy
      ;
  char busybox_cmd[128] =
      "ls\n"
      "echo 123\n"
      "cd /root/benchmark\n"
      "ls\n"
      "./stream\n"
      "ls\n"
      "cd /root/redis\n"
      "ls\n"
      "ifconfig -a\n"
      "./redis-server\n";
  char debian_cmd[128] = "root\n";
  const char *buf = getenv("DIFFTEST_UART_PRESET");
  if (buf == NULL) {
    buf = "";
  }
  int i;
  for (i = 0; i < strlen(buf); i++) {
    uart_enqueue(buf[i]);
  }
}

void init_uart(void) {
  preset_input();
}

void finish_uart(void) {
  restore_stdin();
  memset(queue, 0, sizeof(queue));
  f = 0;
  r = 0;
}
