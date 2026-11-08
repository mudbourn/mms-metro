-- mms-metro platform signage for CC:Tweaked monitors, run as metro_sign [send <channel> | show <channel>]

-- Sign settings, used as-is when no station block or sender is available
local CONFIG = {
  station = "Central",
  line = "Line 1",
  lineColor = "red",
  direction = "Northbound",
  nextStation = "Harbour",
  transferLine = "",
  exitSide = "Left",
  terminus = false,
  textScale = 1,
  etaPadding = 1.1,
  etaStepSeconds = 15,
  refreshSeconds = 1,
  frameSeconds = 0.25,
  scrollGap = 4,
  scrollPauseFrames = 8,
  signalTimeoutSeconds = 10,
}

-- Rednet protocol shared by senders and receivers
local PROTOCOL = "mms_metro_sign"

-- Line colour names mapped to CC colours
local COLOR_NAMES = {
  white = colors.white,
  orange = colors.orange,
  magenta = colors.magenta,
  light_blue = colors.lightBlue,
  yellow = colors.yellow,
  lime = colors.lime,
  pink = colors.pink,
  gray = colors.gray,
  light_gray = colors.lightGray,
  cyan = colors.cyan,
  purple = colors.purple,
  blue = colors.blue,
  brown = colors.brown,
  green = colors.green,
  red = colors.red,
  black = colors.black,
}

-- Resolve a colour name, falling back to white
local function lineColour(name)
  return COLOR_NAMES[string.lower(name or "")] or colors.white
end

-- Pick black or white text for readability on a background
local function textOn(bg)
  if bg == colors.white or bg == colors.yellow or bg == colors.lime
    or bg == colors.lightBlue or bg == colors.lightGray or bg == colors.pink then
    return colors.black
  end
  return colors.white
end

-- Frames drawn so far, drives the scrolling of text too wide for its row
local scrollFrame = 0

-- True during the lit half of each blink second
local function blinkOn()
  return math.floor(os.clock()) % 2 == 0
end

-- The visible slice of text for this frame, scrolling it when wider than width
local function scrolled(text, width)
  if #text <= width then
    return text
  end
  local loop = text .. string.rep(" ", CONFIG.scrollGap)
  local cycle = #loop + CONFIG.scrollPauseFrames
  local offset = math.max(0, scrollFrame % cycle - CONFIG.scrollPauseFrames)
  return (loop .. loop):sub(offset + 1, offset + width)
end

-- Write text centred on a row, scrolling it when too wide
local function centre(mon, y, text, fg, bg)
  local w = mon.getSize()
  mon.setCursorPos(1, y)
  mon.setBackgroundColor(bg)
  mon.write(string.rep(" ", w))
  text = scrolled(text, w)
  local x = math.max(1, math.floor((w - #text) / 2) + 1)
  mon.setCursorPos(x, y)
  mon.setTextColor(fg)
  mon.write(text:sub(1, w))
end

-- Write a label and value on one row
local function row(mon, y, label, value, accent)
  local w = mon.getSize()
  mon.setBackgroundColor(colors.black)
  mon.setCursorPos(1, y)
  mon.write(string.rep(" ", w))
  mon.setCursorPos(2, y)
  mon.setTextColor(colors.lightGray)
  mon.write(label:sub(1, w - 1))
  mon.setTextColor(accent or colors.white)
  mon.write(" " .. scrolled(value, math.max(0, w - #label - 2)))
end

-- Write a label on the left and a status flush right on one row
local function split(mon, y, label, status, accent)
  local w = mon.getSize()
  mon.setBackgroundColor(colors.black)
  mon.setCursorPos(1, y)
  mon.write(string.rep(" ", w))
  mon.setCursorPos(2, y)
  mon.setTextColor(colors.white)
  mon.write(label:sub(1, math.max(0, w - #status - 3)))
  mon.setCursorPos(math.max(1, w - #status), y)
  mon.setTextColor(accent)
  mon.write(status)
end

-- Format seconds as m:ss
local function clock(seconds)
  return string.format("%d:%02d", math.floor(seconds / 60), seconds % 60)
end

-- Upper-bound ETA in seconds, padded for holds behind other trains, rounded up to the step
local function upperSeconds(seconds)
  local step = CONFIG.etaStepSeconds
  return math.max(step, math.ceil(seconds * CONFIG.etaPadding / step) * step)
end

-- Live station settings and arrivals, or CONFIG and no arrivals without a station; arrivals are nil when the read failed
local function readStation(station)
  if not station then
    return CONFIG, nil
  end
  local ok, info = pcall(station.getStation)
  local okArr, arrivals = pcall(station.getArrivals)
  if not ok then
    return CONFIG, nil
  end
  return {
    station = info.name,
    line = info.line,
    lineColor = info.lineColor,
    direction = info.direction,
    nextStation = info.nextStation,
    transferLine = info.hub and info.transferLine or "",
    exitSide = info.exitSide,
    terminus = info.terminus,
  }, okArr and arrivals or nil
end

-- Status text and colour for one arrival
local function arrivalStatus(a, blink)
  if a.atPlatform then
    return "dep " .. clock(a.departsIn), blink and colors.lime or colors.green
  end
  return upperSeconds(a.eta) .. "s", colors.white
end

-- Draw up to max arrivals from row y, stopping before row last
local function drawArrivals(mon, y, last, arrivals, blink, compact)
  if not arrivals or #arrivals == 0 then
    if y <= last then
      row(mon, y, "No trains due", "", colors.gray)
    end
    return
  end
  for i = 1, #arrivals do
    if y > last then
      return
    end
    local a = arrivals[i]
    local status, accent = arrivalStatus(a, blink)
    local label = a.direction
    if not compact and a.line ~= "" then
      label = a.line .. " " .. label
    end
    if label == "" then
      label = "Train"
    end
    split(mon, y, label, status, accent)
    y = y + 1
  end
end

-- Full layout for monitors with room for every field
local function drawFull(mon, info, arrivals, blink)
  local _, h = mon.getSize()
  local bar = lineColour(info.lineColor)
  centre(mon, 1, info.line, textOn(bar), bar)
  centre(mon, 2, info.station, colors.white, colors.black)
  local y = 4
  if info.terminus then
    row(mon, y, "This train", "terminates here", colors.orange)
  else
    if info.direction ~= "" then
      row(mon, y, "Towards", info.direction, bar == colors.black and colors.white or bar)
      y = y + 1
    end
    row(mon, y, "Next stop", info.nextStation)
  end
  y = y + 1
  if info.transferLine ~= "" then
    row(mon, y, "Change for", info.transferLine, colors.yellow)
    y = y + 1
  end
  if info.exitSide ~= "" then
    row(mon, y, "Doors open", info.exitSide)
    y = y + 1
  end
  if arrivals then
    drawArrivals(mon, y + 1, h - 1, arrivals, blink, false)
  end
  centre(mon, h, textutils.formatTime(os.time(), true), colors.white, colors.gray)
end

-- Compact layout for short monitors such as a 3x1 wall
local function drawCompact(mon, info, arrivals, blink)
  local _, h = mon.getSize()
  local bar = lineColour(info.lineColor)
  local title = info.station
  if info.line ~= "" then
    title = info.line .. " - " .. title
  end
  centre(mon, 1, title, textOn(bar), bar)
  if h < 2 then
    return
  end
  if info.terminus then
    row(mon, 2, "Terminates here", "", colors.orange)
  else
    row(mon, 2, "Next", info.nextStation)
  end
  if arrivals then
    drawArrivals(mon, 3, h - 1, arrivals, blink, true)
  end
  if h >= 4 then
    centre(mon, h, textutils.formatTime(os.time(), true), colors.white, colors.gray)
  end
end

-- Draw the sign on one monitor, picking the layout by height
local function draw(mon, info, arrivals, blink)
  mon.setTextScale(CONFIG.textScale)
  mon.setBackgroundColor(colors.black)
  mon.clear()
  local w, h = mon.getSize()
  if h >= 12 then
    drawFull(mon, info, arrivals, blink)
  else
    drawCompact(mon, info, arrivals, blink)
  end
  mon.setCursorPos(w, h)
end

-- Draw a no-signal notice on one monitor
local function drawNoSignal(mon, channel)
  mon.setTextScale(CONFIG.textScale)
  mon.setBackgroundColor(colors.black)
  mon.clear()
  local _, h = mon.getSize()
  centre(mon, math.max(1, math.floor(h / 2)), "No signal: " .. channel, colors.orange, colors.black)
end

-- Every attached monitor, so one computer can drive several
local function monitors()
  return { peripheral.find("monitor") }
end

-- Open every wireless modem for rednet, erroring when there is none
local function openWireless()
  local opened = false
  peripheral.find("modem", function(name, modem)
    if modem.isWireless() then
      rednet.open(name)
      opened = true
    end
    return false
  end)
  if not opened then
    error("No wireless or ender modem attached", 0)
  end
end

-- Local or sender mode, reads a station and optionally broadcasts it
local function runLocal(channel)
  local station = peripheral.find("metro_station")
  if channel then
    openWireless()
    if not station then
      error("Sender needs a station block attached", 0)
    end
  end
  local mons = monitors()
  if not channel and #mons == 0 then
    error("No monitor attached", 0)
  end
  local info, arrivals = readStation(station)
  local lastRead = os.clock()
  local timer = os.startTimer(CONFIG.frameSeconds)
  while true do
    if os.clock() - lastRead >= CONFIG.refreshSeconds then
      local fresh
      info, fresh = readStation(station)
      if fresh or not station then
        arrivals = fresh
      end
      lastRead = os.clock()
      if channel then
        rednet.broadcast({ channel = channel, info = info, arrivals = arrivals }, PROTOCOL)
      end
    end
    for _, mon in ipairs(mons) do
      draw(mon, info, arrivals, blinkOn())
    end
    scrollFrame = scrollFrame + 1
    repeat
      local event, id = os.pullEvent()
      local redraw = event == "timer" and id == timer
      if redraw then
        timer = os.startTimer(CONFIG.frameSeconds)
      elseif event == "peripheral" or event == "peripheral_detach" or event == "monitor_resize" then
        mons = monitors()
        station = peripheral.find("metro_station")
        redraw = true
      end
    until redraw
  end
end

-- Receiver mode, draws the latest broadcast for a channel
local function runShow(channel)
  openWireless()
  local mons = monitors()
  if #mons == 0 then
    error("No monitor attached", 0)
  end
  local latest = nil
  local lastHeard = -math.huge
  local timer = os.startTimer(CONFIG.frameSeconds)
  while true do
    local fresh = latest and os.clock() - lastHeard <= CONFIG.signalTimeoutSeconds
    for _, mon in ipairs(mons) do
      if fresh then
        draw(mon, latest.info, latest.arrivals, blinkOn())
      else
        drawNoSignal(mon, channel)
      end
    end
    scrollFrame = scrollFrame + 1
    repeat
      local event, a, b, c = os.pullEvent()
      local redraw = event == "timer" and a == timer
      if redraw then
        timer = os.startTimer(CONFIG.frameSeconds)
      elseif event == "rednet_message" and c == PROTOCOL and type(b) == "table" and b.channel == channel then
        latest = b
        lastHeard = os.clock()
      elseif event == "peripheral" or event == "peripheral_detach" or event == "monitor_resize" then
        mons = monitors()
        redraw = true
      end
    until redraw
  end
end

local args = { ... }
if args[1] == "send" and args[2] then
  runLocal(args[2])
elseif args[1] == "show" and args[2] then
  runShow(args[2])
elseif args[1] == nil then
  runLocal(nil)
else
  print("Usage: metro_sign [send <channel> | show <channel>]")
end
